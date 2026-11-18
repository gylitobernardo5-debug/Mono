#!/usr/bin/env python3
"""
Patches upstream Dropbear (dbclient) to add native SOCKS5 dynamic port forwarding (-D [bind_addr:]port)
on top of Dropbear's direct-tcpip channel multiplexer, plus Android-friendly status logging
and TCP_NODELAY optimization for G-VPN Extreme Speed Engine V2.
"""
import pathlib
import sys

def replace_once(path: pathlib.Path, old: str, new: str):
    text = path.read_text()
    if new in text:
        print(f"Already patched: {path}")
        return
    if old not in text:
        raise RuntimeError(f"Pattern not found in {path}:\n{old}")
    path.write_text(text.replace(old, new, 1))
    print(f"Patched: {path}")

def main():
    src = pathlib.Path(sys.argv[1]) / "src"

    # 1. forward.h: add socks5 enum value and is_socks5 flag
    replace_once(
        src / "forward.h",
        "enum {direct, forwarded} fwd_type;",
        "enum {direct, forwarded, socks5} fwd_type;",
    )
    replace_once(
        src / "forward.h",
        "unsigned int have_reply; /* is set to 1 after a reply has been received",
        "int is_socks5;\n\tunsigned int have_reply; /* is set to 1 after a reply has been received",
    )

    # 2. cli-runopts.c: add OPT_SOCKS5FWD, -D flag handling, and addforward_socks5()
    replace_once(
        src / "cli-runopts.c",
        "#if DROPBEAR_CLI_LOCALTCPFWD\n\t\tOPT_LOCALTCPFWD,\n#endif",
        "#if DROPBEAR_CLI_LOCALTCPFWD\n\t\tOPT_LOCALTCPFWD,\n\t\tOPT_SOCKS5FWD,\n#endif",
    )
    replace_once(
        src / "cli-runopts.c",
        """#if DROPBEAR_CLI_LOCALTCPFWD
\t\t\t\tcase 'L':
\t\t\t\t\topt = OPT_LOCALTCPFWD;
\t\t\t\t\tbreak;""",
        """#if DROPBEAR_CLI_LOCALTCPFWD
\t\t\t\tcase 'L':
\t\t\t\t\topt = OPT_LOCALTCPFWD;
\t\t\t\t\tbreak;
\t\t\t\tcase 'D':
\t\t\t\t\topt = OPT_SOCKS5FWD;
\t\t\t\t\tbreak;""",
    )
    replace_once(
        src / "cli-runopts.c",
        """\t\t\t\tcase 'D':
#if !DROPBEAR_CLI_REMOTETCPFWD""",
        """#if !DROPBEAR_CLI_LOCALTCPFWD
\t\t\t\tcase 'D':
#endif
#if !DROPBEAR_CLI_REMOTETCPFWD""",
    )
    replace_once(
        src / "cli-runopts.c",
        """#if DROPBEAR_CLI_LOCALTCPFWD
\t\tif (opt == OPT_LOCALTCPFWD) {
\t\t\tTRACE(("opt localtcpfwd"))
\t\t\taddforward(&argv[i][j], cli_opts.localfwds);
\t\t}
\t\telse
#endif""",
        """#if DROPBEAR_CLI_LOCALTCPFWD
\t\tif (opt == OPT_LOCALTCPFWD) {
\t\t\tTRACE(("opt localtcpfwd"))
\t\t\taddforward(&argv[i][j], cli_opts.localfwds);
\t\t}
\t\telse if (opt == OPT_SOCKS5FWD) {
\t\t\tTRACE(("opt socks5fwd"))
\t\t\taddforward_socks5(&argv[i][j], cli_opts.localfwds);
\t\t}
\t\telse
#endif""",
    )
    replace_once(
        src / "cli-runopts.c",
        "static void addforward(const char* str, m_list *fwdlist);",
        "static void addforward(const char* str, m_list *fwdlist);\nstatic void addforward_socks5(const char* origstr, m_list *fwdlist);",
    )
    replace_once(
        src / "cli-runopts.c",
        """\tnewfwd->have_reply = 0;
\tlist_append(fwdlist, newfwd);

\tTRACE(("leave addforward: done"))
\treturn;

fail:
\tdropbear_exit("Bad TCP forward '%s'", origstr);

badport:
\tdropbear_exit("Bad TCP port in '%s'", origstr);
}
#endif""",
        """\tnewfwd->is_socks5 = 0;
\tnewfwd->have_reply = 0;
\tlist_append(fwdlist, newfwd);

\tTRACE(("leave addforward: done"))
\treturn;

fail:
\tdropbear_exit("Bad TCP forward '%s'", origstr);

badport:
\tdropbear_exit("Bad TCP port in '%s'", origstr);
}

static void addforward_socks5(const char* origstr, m_list *fwdlist) {
\tchar *str = m_strdup(origstr);
\tchar *colon = strrchr(str, ':');
\tchar *listenaddr = NULL;
\tchar *listenport = NULL;
\tstruct TCPFwdEntry* newfwd = m_malloc(sizeof(struct TCPFwdEntry));

\tif (colon) {
\t\t*colon = '\\0';
\t\tlistenaddr = str;
\t\tlistenport = colon + 1;
\t} else {
\t\tlistenaddr = m_strdup("127.0.0.1");
\t\tlistenport = str;
\t}

\tif (m_str_to_uint(listenport, &newfwd->listenport) == DROPBEAR_FAILURE || newfwd->listenport == 0 || newfwd->listenport > 65535) {
\t\tdropbear_exit("Bad SOCKS5 listen port in '%s'", origstr);
\t}

\tnewfwd->listenaddr = listenaddr;
\tnewfwd->connectaddr = m_strdup("socks5");
\tnewfwd->connectport = 0;
\tnewfwd->is_socks5 = 1;
\tnewfwd->have_reply = 0;
\tlist_append(fwdlist, newfwd);
}
#endif""",
    )

    # 3. cli-tcpfwd.c: pass is_socks5 into cli_localtcp
    replace_once(
        src / "cli-tcpfwd.c",
        """static int cli_localtcp(const char* listenaddr, 
\t\tunsigned int listenport, 
\t\tconst char* remoteaddr,
\t\tunsigned int remoteport);""",
        """static int cli_localtcp(const char* listenaddr, 
\t\tunsigned int listenport, 
\t\tconst char* remoteaddr,
\t\tunsigned int remoteport,
\t\tint is_socks5);""",
    )
    replace_once(
        src / "cli-tcpfwd.c",
        """\t\tret = cli_localtcp(
\t\t\t\tfwd->listenaddr,
\t\t\t\tfwd->listenport,
\t\t\t\tfwd->connectaddr,
\t\t\t\tfwd->connectport);""",
        """\t\tret = cli_localtcp(
\t\t\t\tfwd->listenaddr,
\t\t\t\tfwd->listenport,
\t\t\t\tfwd->connectaddr,
\t\t\t\tfwd->connectport,
\t\t\t\tfwd->is_socks5);""",
    )
    replace_once(
        src / "cli-tcpfwd.c",
        """static int cli_localtcp(const char* listenaddr, 
\t\tunsigned int listenport, 
\t\tconst char* remoteaddr,
\t\tunsigned int remoteport) {""",
        """static int cli_localtcp(const char* listenaddr, 
\t\tunsigned int listenport, 
\t\tconst char* remoteaddr,
\t\tunsigned int remoteport,
\t\tint is_socks5) {""",
    )
    replace_once(
        src / "cli-tcpfwd.c",
        """\ttcpinfo->chantype = &cli_chan_tcplocal;
\ttcpinfo->fwd_type = direct;

\tret = listen_tcpfwd(tcpinfo, NULL);""",
        """\ttcpinfo->chantype = &cli_chan_tcplocal;
\ttcpinfo->fwd_type = is_socks5 ? socks5 : direct;

\tret = listen_tcpfwd(tcpinfo, NULL);
\tif (ret == DROPBEAR_SUCCESS && is_socks5) {
\t\tfprintf(stderr, "G_DROPBEAR_SOCKS5_LISTENING:%u\\n", listenport);
\t\tfflush(stderr);
\t}""",
    )

    # 4. tcp-accept.c: handle SOCKS5 handshake on accepted socket when fwd_type == socks5
    replace_once(
        src / "tcp-accept.c",
        "#include \"runopts.h\"\n",
        "#include \"runopts.h\"\n#include <arpa/inet.h>\n#include <netinet/tcp.h>\n",
    )
    replace_once(
        src / "tcp-accept.c",
        """static void tcp_acceptor(const struct Listener *listener, int sock) {

\tint fd;
\tstruct sockaddr_storage sa;
\tsocklen_t len;
\tchar ipstring[NI_MAXHOST], portstring[NI_MAXSERV];
\tstruct FwdListener *tcpinfo = (struct FwdListener*)(listener->typedata);

\tlen = sizeof(sa);

\tfd = accept(sock, (struct sockaddr*)&sa, &len);
\tif (fd < 0) {
\t\treturn;
\t}

\tif (getnameinfo((struct sockaddr*)&sa, len, ipstring, sizeof(ipstring),
\t\t\t\tportstring, sizeof(portstring), 
\t\t\t\tNI_NUMERICHOST | NI_NUMERICSERV) != 0) {
\t\tm_close(fd);
\t\treturn;
\t}

\tif (send_msg_channel_open_init(fd, tcpinfo->chantype) == DROPBEAR_SUCCESS) {
\t\tchar* addr = NULL;
\t\tunsigned int port = 0;

\t\tif (tcpinfo->fwd_type == direct) {
\t\t\t/* "direct-tcpip" */
\t\t\t/* host to connect, port to connect */
\t\t\taddr = tcpinfo->sendaddr;
\t\t\tport = tcpinfo->sendport;
\t\t} else {
\t\t\tdropbear_assert(tcpinfo->fwd_type == forwarded);
\t\t\t/* "forwarded-tcpip" */
\t\t\t/* address that was connected, port that was connected */
\t\t\taddr = tcpinfo->request_listenaddr;
\t\t\tport = tcpinfo->listenport;
\t\t}""",
        """static int read_exact(int fd, unsigned char *buf, size_t n) {
\tsize_t off = 0;
\twhile (off < n) {
\t\tssize_t r = read(fd, buf + off, n - off);
\t\tif (r < 0) {
\t\t\tif (errno == EINTR) continue;
\t\t\treturn -1;
\t\t}
\t\tif (r == 0) return -1;
\t\toff += (size_t)r;
\t}
\treturn 0;
}

static int write_exact(int fd, const unsigned char *buf, size_t n) {
\tsize_t off = 0;
\twhile (off < n) {
\t\tssize_t w = write(fd, buf + off, n - off);
\t\tif (w < 0) {
\t\t\tif (errno == EINTR) continue;
\t\t\treturn -1;
\t\t}
\t\toff += (size_t)w;
\t}
\treturn 0;
}

static int handle_socks5_handshake(int fd, char *out_addr, size_t out_len, unsigned int *out_port) {
\tstruct timeval tv;
\tunsigned char hdr[4];
\tunsigned char methods[255];
\tunsigned char resp[2] = {0x05, 0x00};
\tunsigned char reply[10] = {0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0};

\ttv.tv_sec = 2;
\ttv.tv_usec = 0;
\tsetsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
\tsetsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

\tif (read_exact(fd, hdr, 2) < 0 || hdr[0] != 0x05 || hdr[1] == 0) return -1;
\tif (read_exact(fd, methods, hdr[1]) < 0) return -1;
\tif (write_exact(fd, resp, 2) < 0) return -1;

\tif (read_exact(fd, hdr, 4) < 0 || hdr[0] != 0x05 || hdr[1] != 0x01) {
\t\treply[1] = 0x07;
\t\twrite_exact(fd, reply, 10);
\t\treturn -1;
\t}

\tif (hdr[3] == 0x01) {
\t\tunsigned char ip4[4];
\t\tif (read_exact(fd, ip4, 4) < 0) return -1;
\t\tif (!inet_ntop(AF_INET, ip4, out_addr, (socklen_t)out_len)) return -1;
\t} else if (hdr[3] == 0x03) {
\t\tunsigned char dlen = 0;
\t\tif (read_exact(fd, &dlen, 1) < 0 || dlen == 0 || (size_t)dlen >= out_len) return -1;
\t\tif (read_exact(fd, (unsigned char *)out_addr, dlen) < 0) return -1;
\t\tout_addr[dlen] = '\\0';
\t} else if (hdr[3] == 0x04) {
\t\tunsigned char ip6[16];
\t\tif (read_exact(fd, ip6, 16) < 0) return -1;
\t\tif (!inet_ntop(AF_INET6, ip6, out_addr, (socklen_t)out_len)) return -1;
\t} else {
\t\treply[1] = 0x08;
\t\twrite_exact(fd, reply, 10);
\t\treturn -1;
\t}

\tunsigned char pbuf[2];
\tif (read_exact(fd, pbuf, 2) < 0) return -1;
\t*out_port = ((unsigned int)pbuf[0] << 8) | (unsigned int)pbuf[1];

\tif (write_exact(fd, reply, 10) < 0) return -1;

\ttv.tv_sec = 0;
\ttv.tv_usec = 0;
\tsetsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
\tsetsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
\treturn 0;
}

static void tcp_acceptor(const struct Listener *listener, int sock) {
\tint fd;
\tstruct sockaddr_storage sa;
\tsocklen_t len;
\tchar ipstring[NI_MAXHOST], portstring[NI_MAXSERV];
\tchar socks_addr[256];
\tunsigned int socks_port = 0;
\tint nodelay = 1;
\tstruct FwdListener *tcpinfo = (struct FwdListener*)(listener->typedata);

\tlen = sizeof(sa);
\tfd = accept(sock, (struct sockaddr*)&sa, &len);
\tif (fd < 0) {
\t\treturn;
\t}

\tsetsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));

\tif (getnameinfo((struct sockaddr*)&sa, len, ipstring, sizeof(ipstring),
\t\t\t\tportstring, sizeof(portstring), 
\t\t\t\tNI_NUMERICHOST | NI_NUMERICSERV) != 0) {
\t\tm_close(fd);
\t\treturn;
\t}

\tif (tcpinfo->fwd_type == socks5) {
\t\tif (handle_socks5_handshake(fd, socks_addr, sizeof(socks_addr), &socks_port) < 0) {
\t\t\tm_close(fd);
\t\t\treturn;
\t\t}
\t}

\tif (send_msg_channel_open_init(fd, tcpinfo->chantype) == DROPBEAR_SUCCESS) {
\t\tchar* addr = NULL;
\t\tunsigned int port = 0;

\t\tif (tcpinfo->fwd_type == socks5) {
\t\t\taddr = socks_addr;
\t\t\tport = socks_port;
\t\t} else if (tcpinfo->fwd_type == direct) {
\t\t\t/* "direct-tcpip" */
\t\t\t/* host to connect, port to connect */
\t\t\taddr = tcpinfo->sendaddr;
\t\t\tport = tcpinfo->sendport;
\t\t} else {
\t\t\tdropbear_assert(tcpinfo->fwd_type == forwarded);
\t\t\t/* "forwarded-tcpip" */
\t\t\t/* address that was connected, port that was connected */
\t\t\taddr = tcpinfo->request_listenaddr;
\t\t\tport = tcpinfo->listenport;
\t\t}""",
    )

    # 5. cli-session.c: emit clear session running marker on stderr
    replace_once(
        src / "cli-session.c",
        """\t\t\tTRACE(("leave cli_sessionloop: running"))
\t\t\tcli_ses.state = SESSION_RUNNING;""",
        """\t\t\tTRACE(("leave cli_sessionloop: running"))
\t\t\tfprintf(stderr, "G_DROPBEAR_SESSION_RUNNING\\n");
\t\t\tfflush(stderr);
\t\t\tcli_ses.state = SESSION_RUNNING;""",
    )

    # 6. cli-auth.c: guard getpass() on __ANDROID__ (uses DROPBEAR_PASSWORD env var)
    replace_once(
        src / "cli-auth.c",
        """\tif (!cli_opts.batch_mode) {
\t\tpassword = getpass(prompt);
\t}""",
        """\tif (!cli_opts.batch_mode) {
#ifdef __ANDROID__
\t\t(void)prompt;
\t\tpassword = NULL;
#else
\t\tpassword = getpass(prompt);
#endif
\t}""",
    )

if __name__ == "__main__":
    main()
