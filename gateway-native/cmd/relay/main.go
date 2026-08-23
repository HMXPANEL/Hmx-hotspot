// Command relay is a lightweight UDP packet forwarder for HMX Phase 6.
//
// It forwards opaque (encrypted WireGuard) datagrams between a consumer and a
// preconfigured provider endpoint. It never decrypts traffic and holds no keys:
//
//	consumer ──► relay(learned addr) ──► provider endpoint (from control plane)
//	provider ◄── relay ──► consumer last-seen addr
//
// Safeguards (Phase 6 minimum): max sessions, idle-session cleanup, packet
// size cap, registration token required. Hardening belongs to Phase 7.
package main

import (
	"bytes"
	"encoding/hex"
	"log"
	"os"
	"net"
	"strings"
	"sync"
	"time"
)

const (
	maxPacketSize  = 1500 // WireGuard MTU is 1280; anything larger is not ours
	idleTimeout    = 60 * time.Second
	regMagic       = "HMXRELAY1 "
	maxSessions    = 256
	providerWindow = 30 * time.Second // how long we wait for first provider packet
)

type session struct {
	mu        sync.Mutex
	token     string
	target    *net.UDPAddr // fixed provider endpoint from allocation
	lastUser  *net.UDPAddr // learned consumer address
	lastSeen  time.Time
	createdAt time.Time
}

type relay struct {
	conn     *net.UDPConn
	mu       sync.RWMutex
	sessions map[string]*session // by token
}

func newRelay(addr string) (*relay, error) {
	a, err := net.ResolveUDPAddr("udp", addr)
	if err != nil {
		return nil, err
	}
	conn, err := net.ListenUDP("udp", a)
	if err != nil {
		return nil, err
	}
	r := &relay{conn: conn, sessions: make(map[string]*session)}
	go r.reaper()
	return r, nil
}

func (r *relay) reaper() {
	t := time.NewTicker(idleTimeout / 2)
	for range t.C {
		r.mu.Lock()
		for tok, s := range r.sessions {
			s.mu.Lock()
			idle := time.Since(s.lastSeen) > idleTimeout
			unclaimed := time.Since(s.createdAt) > providerWindow && s.lastUser == nil
			s.mu.Unlock()
			if idle || unclaimed {
				delete(r.sessions, tok)
			}
		}
		r.mu.Unlock()
	}
}

func validToken(tok string) bool {
	if len(tok) < 16 || len(tok) > 64 {
		return false
	}
	_, err := hex.DecodeString(tok)
	return err == nil
}

func (r *relay) handle(buf []byte, n int, from *net.UDPAddr) {
	if n > maxPacketSize {
		return
	}
	payload := buf[:n]
	// Registration: "HMXRELAY1 <hex-token>"
	if bytes.HasPrefix(payload, []byte(regMagic)) {
		tok := strings.TrimSpace(string(payload[len(regMagic):]))
		if !validToken(tok) {
			return
		}
		r.mu.Lock()
		defer r.mu.Unlock()
		if len(r.sessions) >= maxSessions {
			return
		}
		// The registration alone does not carry the target; target arrives with
		// the session's first data packet path via ALLOC payload below.
		if _, ok := r.sessions[tok]; ok {
			r.conn.WriteToUDP([]byte("HMXRELAY_OK"), from)
		} else {
			// Unknown token: store pending until ALLOC line completes it.
			r.sessions["pending:"+tok] = &session{token: tok, lastUser: from, lastSeen: time.Now(), createdAt: time.Now()}
		}
		return
	}
	// Allocation: "HMXALLOC <token> <provider-ip:port>" sent by consumer before data.
	if bytes.HasPrefix(payload, []byte("HMXALLOC ")) {
		parts := strings.Fields(string(payload))
		if len(parts) != 3 {
			return
		}
		tok, tgt := parts[1], parts[2]
		if !validToken(tok) {
			return
		}
		ta, err := net.ResolveUDPAddr("udp", tgt)
		if err != nil || ta.IP == nil || ta.Port == 0 {
			return
		}
		r.mu.Lock()
		defer r.mu.Unlock()
		s := r.sessions["pending:"+tok]
		if s == nil {
			if _, exists := r.sessions[tok]; exists {
				return
			}
			if len(r.sessions) >= maxSessions {
				return
			}
			s = &session{token: tok, lastUser: from, lastSeen: time.Now(), createdAt: time.Now()}
		}
		s.target = ta
		delete(r.sessions, "pending:"+tok)
		r.sessions[tok] = s
		r.conn.WriteToUDP([]byte("HMXRELAY_READY"), from)
		return
	}
	// Data plane: bidirectional forwarding for allocated sessions.
	//   consumer -> relay -> fixed provider target
	//   provider -> relay -> last-seen consumer addr
	r.mu.RLock()
	var sess *session
	for _, s := range r.sessions {
		if strings.HasPrefix(s.token, "pending") {
			continue
		}
		s.mu.Lock()
		// Adopt-once: the first data source after allocation becomes the
		// consumer (token secrecy is the auth); afterwards the addr is locked.
		if s.target != nil && s.lastUser == nil && !udpEqual(s.target, from) {
			s.lastUser = from
		}
		isUser := s.lastUser != nil && udpEqual(s.lastUser, from)
		isProv := s.target != nil && udpEqual(s.target, from)
		if isUser || isProv {
			sess = s
		}
		s.mu.Unlock()
		if sess != nil {
			break
		}
	}
	r.mu.RUnlock()
	if sess == nil {
		return
	}
	sess.mu.Lock()
	sess.lastSeen = time.Now()
	dst := sess.target
	if udpEqual(from, sess.target) {
		dst = sess.lastUser // provider -> consumer
		if dst == nil {
			sess.mu.Unlock()
			return
		}
	}
	sess.mu.Unlock()
	if dst == nil {
		return
	}
	r.conn.WriteToUDP(payload, dst)
}

func udpEqual(a, b *net.UDPAddr) bool {
	return a.IP.Equal(b.IP) && a.Port == b.Port
}

func (r *relay) serve() {
	buf := make([]byte, maxPacketSize+1)
	for {
		n, from, err := r.conn.ReadFromUDP(buf)
		if err != nil {
			log.Printf("read error: %v", err)
			continue
		}
		pkt := append([]byte(nil), buf[:n]...)
		r.handle(pkt, n, from)
	}
}

func main() {
	addr := ":51821"
	if len(os.Args) > 1 {
		addr = os.Args[1]
	}
	r, err := newRelay(addr)
	if err != nil {
		log.Fatal(err)
	}
	log.Printf("relay listening on %s", addr)
	r.serve()
}
