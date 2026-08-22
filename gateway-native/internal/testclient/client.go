package testclient

import (
	"fmt"
	"net/netip"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

const defaultMTU = 1280

// ClientConfig describes the Phone-B-side test peer. It uses the stock
// wireguard-go netstack wrapper (client role), mirroring what the official
// Android tunnel library does on a real device.
type ClientConfig struct {
	PrivateKeyHex   string
	GatewayPubHex   string
	GatewayEndpoint string
	InnerIPv4       string
	DNS             string
	MTU             int
}

type Client struct {
	Dev *device.Device
	Net *netstack.Net
}

func StartClient(cfg ClientConfig) (c *Client, err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("client start panicked: %v", r)
		}
	}()
	return startClient(cfg)
}

func startClient(cfg ClientConfig) (*Client, error) {
	if cfg.MTU <= 0 {
		cfg.MTU = defaultMTU
	}
	inner := netip.MustParseAddr(cfg.InnerIPv4)
	dns := inner
	if cfg.DNS != "" {
		dns = netip.MustParseAddr(cfg.DNS)
	}
	tdev, tnet, err := netstack.CreateNetTUN([]netip.Addr{inner}, []netip.Addr{dns}, cfg.MTU)
	if err != nil {
		return nil, fmt.Errorf("CreateNetTUN: %w", err)
	}
	wgLog := &device.Logger{
		Verbosef: func(string, ...any) {},
		Errorf:   func(f string, a ...any) { fmt.Printf("[hmx-client] "+f+"\n", a...) },
	}
	dev := device.NewDevice(tdev, conn.NewDefaultBind(), wgLog)
	ipc := "private_key=" + cfg.PrivateKeyHex +
		"\npublic_key=" + cfg.GatewayPubHex +
		"\nendpoint=" + cfg.GatewayEndpoint +
		"\nallowed_ip=0.0.0.0/0" +
		"\nallowed_ip=::/0" +
		"\npersistent_keepalive_interval=25\n"
	if err := dev.IpcSet(ipc); err != nil {
		dev.Close()
		return nil, fmt.Errorf("IpcSet: %w", err)
	}
	dev.Up()
	return &Client{Dev: dev, Net: tnet}, nil
}

func (c *Client) Close() { c.Dev.Close() }
