package hmxgateway

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"testing"
	"time"

	"hmx/gateway-native/internal/netutil"
	"hmx/gateway-native/internal/testclient"
)

func udpAddrPort(a *net.UDPAddr) netip.AddrPort {
	ip, _ := netip.AddrFromSlice(a.IP)
	return netip.AddrPortFrom(ip.Unmap(), uint16(a.Port))
}

func tcpEchoServer(t *testing.T) string {
	t.Helper()
	var ln net.Listener
	var err error
	for i := 0; i < 3; i++ {
		ln, err = net.Listen("tcp", netutil.PrimaryIPv4OrLoop()+":0")
		if err == nil {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				io.Copy(c, c)
				c.Close()
			}()
		}
	}()
	return ln.Addr().String()
}

func udpEchoServer(t *testing.T) netip.AddrPort {
	t.Helper()
	pc, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.ParseIP(netutil.PrimaryIPv4OrLoop()), Port: 0})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	go func() {
		buf := make([]byte, 2048)
		for {
			n, ra, err := pc.ReadFromUDP(buf)
			if err != nil {
				return
			}
			pc.WriteToUDP(buf[:n], ra)
		}
	}()
	return udpAddrPort(pc.LocalAddr().(*net.UDPAddr))
}

func startPair(t *testing.T, tweak func(*Config)) (*Gateway, *testclient.Client) {
	t.Helper()
	gwKp, err := GenerateKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	clKp, err := GenerateKeyPair()
	if err != nil {
		t.Fatal(err)
	}
	gwPriv, gwPub, clPriv, clPub := gwKp.PrivHex, gwKp.PubHex, clKp.PrivHex, clKp.PubHex
	for attempt := 0; ; attempt++ {
		pc, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
		if err != nil {
			t.Fatal(err)
		}
		port := pc.LocalAddr().(*net.UDPAddr).Port
		pc.Close()

		cfg := Config{
			PrivateKeyHex: gwPriv,
			ListenPort:    port,
			InnerIPv4:     "10.66.0.1",
			DNSUpstream:   netutil.PrimaryIPv4OrLoop() + ":1",
		}
		if tweak != nil {
			tweak(&cfg)
		}
		gw, err := Start(&cfg)
		if err != nil {
			if attempt < 3 {
				time.Sleep(200 * time.Millisecond)
				continue
			}
			t.Fatal(err)
		}
		if err := gw.AddPeer(clPub, "10.66.0.2/32"); err != nil {
			gw.Stop()
			t.Fatal(err)
		}
		var cl *testclient.Client
		for i := 0; i < 3; i++ {
			cl, err = testclient.StartClient(testclient.ClientConfig{
				PrivateKeyHex:   clPriv,
				GatewayPubHex:   gwPub,
				GatewayEndpoint: fmt.Sprintf("127.0.0.1:%d", port),
				InnerIPv4:       "10.66.0.2",
				DNS:             "10.66.0.1",
			})
			if err == nil {
				break
			}
			time.Sleep(300 * time.Millisecond)
		}
		if err != nil || cl == nil {
			gw.Stop()
			t.Fatal(err)
		}
		t.Cleanup(func() { cl.Close(); gw.Stop(); time.Sleep(300 * time.Millisecond) })
		return gw, cl
	}
}

func awaitHandshake(t *testing.T, cl *testclient.Client) {
	t.Helper()
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		s, err := cl.Dev.IpcGet()
		if err == nil {
			for _, line := range strings.Split(s, "\n") {
				if strings.HasPrefix(line, "last_handshake_time_sec=") {
					if v, _ := strconv.ParseUint(strings.TrimPrefix(line, "last_handshake_time_sec="), 10, 64); v > 0 {
						return
					}
				}
			}
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Fatal("wireguard handshake not established within 15s")
}

func awaitTunnel(t *testing.T, cl *testclient.Client, target string) net.Conn {
	awaitHandshake(t, cl)
	t.Helper()
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		conn, err := cl.Net.DialContext(ctx, "tcp4", target)
		cancel()
		if err == nil {
			return conn
		}
		time.Sleep(250 * time.Millisecond)
	}
	t.Fatalf("tunnel not established within 15s (target %s)", target)
	return nil
}

func TestGatewayTCPLoopback(t *testing.T) {
	payload := []byte("HMX-LOOPBACK-TCP-OK")
	target := tcpEchoServer(t)
	gw, cl := startPair(t, nil)

	conn := awaitTunnel(t, cl, target)
	defer conn.Close()
	if _, err := conn.Write(payload); err != nil {
		t.Fatalf("write through tunnel: %v", err)
	}
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	got := make([]byte, len(payload))
	if _, err := io.ReadFull(conn, got); err != nil {
		t.Fatalf("read echo: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("payload mismatch: %q", got)
	}
	st := gw.Stats()
	if st.RxBytes == 0 || st.TxBytes == 0 {
		t.Fatalf("accounting empty: %+v", st)
	}
}

func TestGatewayUDPLoopback(t *testing.T) {
	payload := []byte("HMX-LOOPBACK-UDP-OK")
	echo := udpEchoServer(t)
	_, cl := startPair(t, nil)

	awaitTunnel(t, cl, tcpEchoServer(t))

	conn, err := cl.Net.DialUDPAddrPort(netip.AddrPort{}, echo)
	if err != nil {
		t.Fatalf("udp dial through tunnel: %v", err)
	}
	defer conn.Close()
	for i := 0; i < 3; i++ {
		if _, err := conn.Write(payload); err != nil {
			t.Fatalf("udp write: %v", err)
		}
		conn.SetReadDeadline(time.Now().Add(5 * time.Second))
		got := make([]byte, len(payload))
		if _, err := io.ReadFull(conn, got); err != nil {
			t.Fatalf("udp read echo: %v", err)
		}
		if !bytes.Equal(got, payload) {
			t.Fatalf("udp payload mismatch: %q", got)
		}
	}
}

func TestGatewayDNSForwarding(t *testing.T) {
	upstream := udpEchoServer(t)
	dnsQuery := []byte{
		0xAB, 0xCD, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
		7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0, 0, 1, 0, 1,
	}
	_, cl := startPair(t, func(c *Config) { c.DNSUpstream = upstream.String() })

	awaitTunnel(t, cl, tcpEchoServer(t))

	conn, err := cl.Net.DialUDPAddrPort(netip.AddrPort{}, netip.MustParseAddrPort("10.66.0.1:53"))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if _, err := conn.Write(dnsQuery); err != nil {
		t.Fatalf("dns query write: %v", err)
	}
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	got := make([]byte, len(dnsQuery)+64)
	n, err := conn.Read(got)
	if err != nil {
		t.Fatalf("dns relay read: %v", err)
	}
	if n < len(dnsQuery) || !bytes.Equal(got[:len(dnsQuery)], dnsQuery) {
		t.Fatalf("dns payload not relayed intact: %d bytes", n)
	}
}

func TestGatewayHardLimitCutsFlows(t *testing.T) {
	hostIP := netutil.PrimaryIPv4OrLoop()
	ln, err := net.Listen("tcp", hostIP+":0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	blob := bytes.Repeat([]byte{0xAB}, 1024*1024)
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				for {
					if _, err := c.Write(blob); err != nil {
						return
					}
				}
			}()
		}
	}()

	gw, cl := startPair(t, func(c *Config) { c.HardLimitBytes = 3 * 1024 * 1024 })

	conn := awaitTunnel(t, cl, ln.Addr().String())
	defer conn.Close()

	buf := make([]byte, 256*1024)
	var total int64
	conn.SetReadDeadline(time.Now().Add(20 * time.Second))
	for {
		n, err := conn.Read(buf)
		total += int64(n)
		if err != nil || gw.Stats().LimitReached {
			break
		}
	}
	if total >= 5*1024*1024 {
		t.Fatalf("hard limit did not cut flow: read %d bytes", total)
	}
	if !gw.Stats().LimitReached {
		t.Fatal("limit flag not set after cut")
	}
	t.Logf("limit test: cut after %.2f MB", float64(total)/(1024*1024))
}

func TestWrongKeyPeerCannotHandshake(t *testing.T) {
	gwKp, _ := GenerateKeyPair()
	badKp, _ := GenerateKeyPair()
	gwPriv, gwPub, badPriv := gwKp.PrivHex, gwKp.PubHex, badKp.PrivHex
	var gw *Gateway
	var port int
	var err error
	for attempt := 0; ; attempt++ {
		pc, perr := net.ListenUDP("udp4", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
		if perr != nil {
			t.Fatal(perr)
		}
		port = pc.LocalAddr().(*net.UDPAddr).Port
		pc.Close()

		gw, err = Start(&Config{PrivateKeyHex: gwPriv, ListenPort: port, InnerIPv4: "10.66.0.1"})
		if err == nil {
			break
		}
		if attempt >= 3 {
			t.Fatal(err)
		}
		time.Sleep(300 * time.Millisecond)
	}
	defer gw.Stop()
	cl, err := testclient.StartClient(testclient.ClientConfig{
		PrivateKeyHex:   badPriv,
		GatewayPubHex:   gwPub,
		GatewayEndpoint: fmt.Sprintf("127.0.0.1:%d", port),
		InnerIPv4:       "10.66.0.2",
		DNS:             "10.66.0.1",
	})
	if err != nil {
		t.Fatal(err)
	}
	defer cl.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 6*time.Second)
	defer cancel()
	conn, err := cl.Net.DialContext(ctx, "tcp4", "10.255.255.1:80")
	if err == nil {
		conn.Close()
		t.Fatal("unexpected success with wrong key")
	}
}
