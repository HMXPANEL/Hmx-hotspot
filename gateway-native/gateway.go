package hmxgateway

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/crypto/curve25519"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

const (
	defaultMTU      = 1280
	dialTimeout     = 10 * time.Second
	udpIdleTimeout  = 30 * time.Second
	defaultMaxConns = 512
	chunkSize       = 32 * 1024
)

type Config struct {
	PrivateKeyHex  string
	ListenPort     int
	InnerIPv4      string
	InnerIPv6      string
	DNSInner       string
	DNSUpstream    string
	MTU            int
	HardLimitBytes int64
	MaxConns       int
}

type Stats struct {
	RxBytes      int64
	TxBytes      int64
	ActiveFlows  int64
	LimitReached bool
}

type Gateway struct {
	cfg      Config
	dev      *device.Device
	tun      *netTun
	rx, tx   atomic.Int64
	flows    atomic.Int64
	limitHit atomic.Bool

	mu    sync.Mutex
	conns map[io.Closer]struct{}
	done  chan struct{}
}

type KeyPair struct {
	PrivHex string
	PubHex  string
}

func GenerateKeyPair() (*KeyPair, error) {
	priv := make([]byte, 32)
	if _, err := rand.Read(priv); err != nil {
		return nil, err
	}
	priv[0] &= 248
	priv[31] &= 127
	priv[31] |= 64
	pub, err := curve25519.X25519(priv, curve25519.Basepoint)
	if err != nil {
		return nil, err
	}
	return &KeyPair{PrivHex: hex.EncodeToString(priv), PubHex: hex.EncodeToString(pub)}, nil
}

func Start(cfg *Config) (g *Gateway, err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("gateway start panicked: %v", r)
		}
	}()
	return startGateway(cfg)
}

func startGateway(cfg *Config) (*Gateway, error) {
	if cfg.MTU <= 0 {
		cfg.MTU = defaultMTU
	}
	if cfg.MaxConns <= 0 {
		cfg.MaxConns = defaultMaxConns
	}
	if cfg.InnerIPv4 == "" {
		return nil, errors.New("InnerIPv4 required")
	}
	if cfg.DNSInner == "" {
		cfg.DNSInner = cfg.InnerIPv4
	}
	priv, err := hex.DecodeString(cfg.PrivateKeyHex)
	if err != nil || len(priv) != 32 {
		return nil, fmt.Errorf("bad private key: %w", err)
	}

	var addrs []netip.Addr
	v4 := netip.MustParseAddr(cfg.InnerIPv4)
	addrs = append(addrs, v4)
	if cfg.InnerIPv6 != "" {
		addrs = append(addrs, netip.MustParseAddr(cfg.InnerIPv6))
	}

	t, err := newNetTun(addrs, cfg.MTU)
	if err != nil {
		return nil, err
	}
	t.tag = "gateway"
	g := &Gateway{
		cfg:   *cfg,
		tun:   t,
		conns: make(map[io.Closer]struct{}),
		done:  make(chan struct{}),
	}
	wgLog := &device.Logger{
		Verbosef: debugLogger("gateway"),
		Errorf:   func(f string, a ...any) { log.Printf("[hmx-gateway] "+f, a...) },
	}
	g.dev = device.NewDevice(t, conn.NewDefaultBind(), wgLog)
	ipc := "private_key=" + cfg.PrivateKeyHex + "\nlisten_port=" + strconv.Itoa(int(cfg.ListenPort)) + "\n"
	if err := g.dev.IpcSet(ipc); err != nil {
		g.dev.Close()
		return nil, fmt.Errorf("IpcSet: %w", err)
	}
	g.dev.Up()

	tcpFwd := tcp.NewForwarder(t.stack, 0, cfg.MaxConns, g.handleTCP)
	t.stack.SetTransportProtocolHandler(tcp.ProtocolNumber, func(id stack.TransportEndpointID, pkt *stack.PacketBuffer) bool {
		debugLogger("tcp-h")("pkt %s:%d -> %s:%d", id.RemoteAddress, id.RemotePort, id.LocalAddress, id.LocalPort)
		return tcpFwd.HandlePacket(id, pkt)
	})
	udpFwd := udp.NewForwarder(t.stack, g.handleUDP)
	t.stack.SetTransportProtocolHandler(udp.ProtocolNumber, udpFwd.HandlePacket)
	return g, nil
}

func (g *Gateway) AddPeer(publicKeyHex, allowedIPCIDR string) error {
	return g.dev.IpcSet("public_key=" + publicKeyHex + "\nallowed_ip=" + allowedIPCIDR + "\n")
}

func (g *Gateway) RemovePeer(publicKeyHex string) error {
	return g.dev.IpcSet("public_key=" + publicKeyHex + "\nremove=true\n")
}

func (g *Gateway) Stats() *Stats {
	return &Stats{
		RxBytes:      g.rx.Load(),
		TxBytes:      g.tx.Load(),
		ActiveFlows:  g.flows.Load(),
		LimitReached: g.limitHit.Load(),
	}
}

func (g *Gateway) Stop() {
	select {
	case <-g.done:
		return
	default:
	}
	close(g.done)
	g.mu.Lock()
	for c := range g.conns {
		c.Close()
	}
	g.conns = make(map[io.Closer]struct{})
	g.mu.Unlock()
	time.Sleep(300 * time.Millisecond)
	g.dev.Close()
}

func (g *Gateway) track(c io.Closer) {
	g.flows.Add(1)
	g.mu.Lock()
	g.conns[c] = struct{}{}
	g.mu.Unlock()
}

func (g *Gateway) untrack(c io.Closer) {
	g.flows.Add(-1)
	c.Close()
	g.mu.Lock()
	delete(g.conns, c)
	g.mu.Unlock()
}

func (g *Gateway) handleTCP(req *tcp.ForwarderRequest) {
	id := req.ID()
	debugLogger("fwd-tcp")("SYN %s:%d -> %s:%d", id.RemoteAddress, id.RemotePort, id.LocalAddress, id.LocalPort)
	if g.limitHit.Load() {
		req.Complete(true)
		return
	}
	var wq waiter.Queue
	ep, terr := req.CreateEndpoint(&wq)
	if terr != nil {
		req.Complete(true)
		return
	}
	dst := net.JoinHostPort(id.LocalAddress.String(), strconv.Itoa(int(id.LocalPort)))
	out, err := net.DialTimeout("tcp", dst, dialTimeout)
	if err != nil {
		debugLogger("fwd-tcp")("host dial FAILED %s: %v", dst, err)
		ep.Close()
		return
	}
	debugLogger("fwd-tcp")("host dial ok %s", dst)
	inner := gonet.NewTCPConn(&wq, ep)
	g.track(inner)
	go func() {
		defer g.untrack(inner)
		g.splice(inner, out)
	}()
}

func (g *Gateway) handleUDP(req *udp.ForwarderRequest) {
	id := req.ID()
	debugLogger("fwd-udp")("flow %s:%d -> %s:%d", id.RemoteAddress, id.RemotePort, id.LocalAddress, id.LocalPort)
	if g.limitHit.Load() {
		return
	}
	var wq waiter.Queue
	ep, terr := req.CreateEndpoint(&wq)
	if terr != nil {
		return
	}
	upstream := net.JoinHostPort(id.LocalAddress.String(), strconv.Itoa(int(id.LocalPort)))
	if id.LocalPort == 53 && id.LocalAddress.String() == g.cfg.DNSInner {
		if g.cfg.DNSUpstream == "" {
			ep.Close()
			return
		}
		upstream = g.cfg.DNSUpstream
	}
	host, err := net.Dial("udp", upstream)
	if err != nil {
		ep.Close()
		return
	}
	inner := gonet.NewUDPConn(&wq, ep)
	g.track(inner)
	go func() {
		defer g.untrack(inner)
		g.udpPump(inner, host.(*net.UDPConn))
	}()
}

func (g *Gateway) splice(a, b net.Conn) {
	done := make(chan struct{}, 2)
	go func() { g.pump(a, b, &g.rx); a.Close(); done <- struct{}{} }()
	go func() { g.pump(b, a, &g.tx); b.Close(); done <- struct{}{} }()
	<-done
	<-done
}

func (g *Gateway) pump(dst io.Writer, src io.Reader, counter *atomic.Int64) error {
	buf := make([]byte, chunkSize)
	for {
		n, rerr := src.Read(buf)
		if n > 0 {
			counter.Add(int64(n))
			if _, werr := dst.Write(buf[:n]); werr != nil {
				return werr
			}
			if g.cfg.HardLimitBytes > 0 && g.rx.Load()+g.tx.Load() >= g.cfg.HardLimitBytes {
				g.limitHit.Store(true)
				return errors.New("hard data limit reached")
			}
		}
		if rerr != nil {
			return rerr
		}
	}
}

func (g *Gateway) udpPump(inner *gonet.UDPConn, host *net.UDPConn) {
	done := make(chan struct{}, 2)
	go func() {
		buf := make([]byte, chunkSize)
		for {
			inner.SetReadDeadline(time.Now().Add(udpIdleTimeout))
			n, err := inner.Read(buf)
			if n > 0 {
				g.rx.Add(int64(n))
				if _, err := host.Write(buf[:n]); err != nil {
					done <- struct{}{}
					return
				}
			}
			if err != nil {
				done <- struct{}{}
				return
			}
		}
	}()
	go func() {
		buf := make([]byte, chunkSize)
		for {
			host.SetReadDeadline(time.Now().Add(udpIdleTimeout))
			n, err := host.Read(buf)
			if n > 0 {
				g.tx.Add(int64(n))
				if _, err := inner.Write(buf[:n]); err != nil {
					done <- struct{}{}
					return
				}
			}
			if err != nil {
				done <- struct{}{}
				return
			}
		}
	}()
	<-done
	g.untrack(inner)
}
