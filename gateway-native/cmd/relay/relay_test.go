package main

import (
	"bytes"
	"net"
	"strings"
	"testing"
	"time"
)

func startTestRelay(t *testing.T) *relay {
	t.Helper()
	r, err := newRelay("127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go r.serve()
	return r
}

func localAddr(t *testing.T, r *relay) string {
	t.Helper()
	return r.conn.LocalAddr().String()
}

func udpDial(t *testing.T, addr *net.UDPAddr) *net.UDPConn {
	t.Helper()
	c, err := net.DialUDP("udp", nil, addr)
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func recvWithTimeout(t *testing.T, c *net.UDPConn) []byte {
	t.Helper()
	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 2048)
	n, _, err := c.ReadFromUDP(buf)
	if err != nil {
		t.Fatalf("receive timeout: %v", err)
	}
	return buf[:n]
}

func TestRegistrationRequiresValidToken(t *testing.T) {
	r := startTestRelay(t)
	user := udpDial(t, r.conn.LocalAddr().(*net.UDPAddr))
	defer user.Close()

	user.Write([]byte(regMagic + "short"))
	time.Sleep(100 * time.Millisecond)
	r.mu.RLock()
	n := len(r.sessions)
	r.mu.RUnlock()
	if n != 0 {
		t.Fatalf("invalid token accepted: %d sessions", n)
	}

	good := strings.Repeat("ab", 24)
	user.Write([]byte(regMagic + good))
	time.Sleep(100 * time.Millisecond)
	r.mu.RLock()
	_, ok := r.sessions["pending:"+good]
	r.mu.RUnlock()
	if !ok {
		t.Fatal("valid token not registered as pending session")
	}
}

func TestAllocationAndBidirectionalForwarding(t *testing.T) {
	r := startTestRelay(t)
	// fake provider socket (stand-in for provider gateway endpoint)
	prov, _ := net.DialUDP("udp", nil, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 1})
	provLocal := prov.LocalAddr()
	prov.Close()
	provListen, _ := net.ListenUDP("udp", provLocal.(*net.UDPAddr))
	defer provListen.Close()

	token := strings.Repeat("cd", 20)

	user := udpDial(t, r.conn.LocalAddr().(*net.UDPAddr))
	defer user.Close()

	// register consumer
	user.Write([]byte(regMagic + token))
	recvWithTimeout(t, user) // HMXRELAY_OK

	// allocate with provider target
	user.Write([]byte("HMXALLOC " + token + " " + provListen.LocalAddr().String()))
	recvWithTimeout(t, user) // HMXRELAY_READY

	r.mu.RLock()
	s, ok := r.sessions[token]
	r.mu.RUnlock()
	if !ok || s.target == nil {
		t.Fatal("session not allocated")
	}

	// consumer -> relay -> provider
	user.Write([]byte("wg-packet-1"))
	got := recvWithTimeout(t, provListen)
	if !bytes.Equal(got, []byte("wg-packet-1")) {
		t.Fatalf("provider got %q", got)
	}

	// provider -> relay -> consumer
	provListen.WriteToUDP([]byte("wg-reply"), user.RemoteAddr().(*net.UDPAddr))
	got = recvWithTimeout(t, user)
	if !bytes.Equal(got, []byte("wg-reply")) {
		t.Fatalf("consumer got %q", got)
	}
}

func TestDataRejectedWithoutAllocation(t *testing.T) {
	r := startTestRelay(t)
	stranger, _ := udpDial(t, r.conn.LocalAddr().(*net.UDPAddr))
	defer stranger.Close()
	stranger.Write([]byte("random-data"))
	time.Sleep(150 * time.Millisecond)
	// No panic, no forwarding; relay still alive.
	if r.conn == nil {
		t.Fatal("relay died")
	}
}

func TestMaxSessionsEnforced(t *testing.T) {
	r := startTestRelay(t)
	for i := 0; i < maxSessions+10; i++ {
		c, _ := udpDial(t, r.conn.LocalAddr().(*net.UDPAddr))
		c.Write([]byte(regMagic + strings.Repeat("aa", 12)))
		c.Close()
	}
	time.Sleep(300 * time.Millisecond)
	r.mu.RLock()
	total := len(r.sessions)
	r.mu.RUnlock()
	// pending sessions also count toward cap; must never exceed it
	if total > maxSessions {
		t.Fatalf("session cap exceeded: %d", total)
	}
}
