// SPDX-License-Identifier: MIT
// Adapted from wireguard-go tun/netstack (Copyright WireGuard LLC) for the
// HMX userspace gateway: same channel-based TUN bridging, but the gVisor
// stack is kept accessible so TCP/UDP forwarders can terminate inbound flows.

package hmxgateway

import (
	"fmt"
	"net/netip"
	"os"
	"syscall"

	"golang.zx2c4.com/wireguard/tun"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/icmp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

type netTun struct {
	tag            string
	ep             *channel.Endpoint
	stack          *stack.Stack
	events         chan tun.Event
	notifyHandle   *channel.NotificationHandle
	incomingPacket chan *buffer.View
	mtu            int
}

func newNetTun(localAddresses []netip.Addr, mtu int) (*netTun, error) {
	opts := stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol, ipv6.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol, icmp.NewProtocol6, icmp.NewProtocol4},
		HandleLocal:        false,
	}
	t := &netTun{
		ep:             channel.New(1024, uint32(mtu), ""),
		stack:          stack.New(opts),
		events:         make(chan tun.Event, 10),
		incomingPacket: make(chan *buffer.View),
		mtu:            mtu,
	}
	sack := tcpip.TCPSACKEnabled(true)
	if err := t.stack.SetTransportProtocolOption(tcp.ProtocolNumber, &sack); err != nil {
		return nil, fmt.Errorf("SACK option: %v", err)
	}
	t.notifyHandle = t.ep.AddNotify(t)
	if err := t.stack.CreateNIC(1, t.ep); err != nil {
		return nil, fmt.Errorf("CreateNIC: %v", err)
	}
	hasV4, hasV6 := false, false
	for _, ip := range localAddresses {
		pn := ipv4.ProtocolNumber
		if ip.Is6() {
			pn = ipv6.ProtocolNumber
		}
		pa := tcpip.ProtocolAddress{
			Protocol:          pn,
			AddressWithPrefix: tcpip.AddrFromSlice(ip.AsSlice()).WithPrefix(),
		}
		if err := t.stack.AddProtocolAddress(1, pa, stack.AddressProperties{}); err != nil {
			return nil, fmt.Errorf("AddProtocolAddress(%v): %v", ip, err)
		}
		if ip.Is4() {
			hasV4 = true
		} else {
			hasV6 = true
		}
	}
	if hasV4 {
		t.stack.AddRoute(tcpip.Route{Destination: header.IPv4EmptySubnet, NIC: 1})
	}
	if hasV6 {
		t.stack.AddRoute(tcpip.Route{Destination: header.IPv6EmptySubnet, NIC: 1})
	}
	t.stack.SetPromiscuousMode(1, true)
	t.stack.SetSpoofing(1, true)
	t.events <- tun.EventUp
	return t, nil
}

func (t *netTun) Name() (string, error) { return "hmx", nil }
func (t *netTun) File() *os.File        { return nil }
func (t *netTun) Events() <-chan tun.Event {
	return t.events
}

func (t *netTun) Read(buf [][]byte, sizes []int, offset int) (int, error) {
	view, ok := <-t.incomingPacket
	if !ok {
		return 0, os.ErrClosed
	}
	n, err := view.Read(buf[0][offset:])
	if err != nil {
		return 0, err
	}
	sizes[0] = n
	return 1, nil
}

func (t *netTun) Write(buf [][]byte, offset int) (int, error) {
	for _, b := range buf {
		packet := b[offset:]
		if len(packet) == 0 {
			continue
		}
		if os.Getenv("HMX_DEBUG") != "" && t.tag != "" {
			fmt.Printf("[tun:%s] rx pkt %dB v%d\n", t.tag, len(packet), packet[0]>>4)
		}
		pkb := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(packet)})
		switch packet[0] >> 4 {
		case 4:
			t.ep.InjectInbound(header.IPv4ProtocolNumber, pkb)
		case 6:
			t.ep.InjectInbound(header.IPv6ProtocolNumber, pkb)
		default:
			return 0, syscall.EAFNOSUPPORT
		}
	}
	return len(buf), nil
}

func (t *netTun) WriteNotify() {
	pkt := t.ep.Read()
	if pkt == nil {
		return
	}
	view := pkt.ToView()
	pkt.DecRef()
	t.incomingPacket <- view
}

func (t *netTun) Close() error {
	t.stack.RemoveNIC(1)
	t.stack.Close()
	t.ep.RemoveNotify(t.notifyHandle)
	t.ep.Close()
	close(t.events)
	close(t.incomingPacket)
	return nil
}

func (t *netTun) MTU() (int, error) { return t.mtu, nil }
func (t *netTun) BatchSize() int    { return 1 }
