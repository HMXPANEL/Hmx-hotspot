// Package netutil holds host-only helpers that are not part of the
// gomobile-bindable API surface (gobind rejects (T, bool) results).
package netutil

import "net"

func PrimaryIPv4() (string, bool) {
	c, err := net.Dial("udp", "8.8.8.8:80")
	if err != nil {
		return "", false
	}
	defer c.Close()
	ip := c.LocalAddr().(*net.UDPAddr).IP.To4()
	if ip == nil || ip.IsLoopback() {
		return "", false
	}
	return ip.String(), true
}

func PrimaryIPv4OrLoop() string {
	if ip, ok := PrimaryIPv4(); ok {
		return ip
	}
	return "127.0.0.1"
}
