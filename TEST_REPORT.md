# Relatório de Testes e Compilação — G-VPN Extreme Speed Engine V2

**Projeto:** G-VPN  
**Application ID:** `com.aistudio.gvpn.gmvmc`  
**Desenvolvedor:** Gylito Bernardo Maula  
**Apoio Técnico:** Dev Muriba MC (`876699965`)  

---

## 1. Compilação do APK e Binários Nativos Dropbear
- **Implementação SSH exclusiva:** Dropbear `2026.94` (`dbclient`) compilado com Android NDK r29 (`clang`, `-fPIE -pie`, `DROPBEAR_USE_PASSWORD_ENV=1`, `DEFAULT_RECV_WINDOW=262144`, `TCP_NODELAY`, suporte nativo `-D` SOCKS5 sobre `direct-tcpip`).
- **APK gerado:** `app/build/outputs/apk/debug/app-debug.apk` (`18 MB`)
- **Binários embutidos e verificados no APK:**
  - `lib/arm64-v8a/libdropbear.so` (295 584 bytes)
  - `lib/armeabi-v7a/libdropbear.so` (264 572 bytes)
  - `lib/x86_64/libdropbear.so` (330 824 bytes)
  - `lib/x86/libdropbear.so` (341 136 bytes)

---

## 2. Resultados da Suíte de Testes (`gradle :app:testDebugUnitTest`)

| Suite de Testes | Testes | Falhas | Erros | Tempo |
|---|---:|---:|---:|---:|
| `com.example.ExampleRobolectricTest` | 5 | 0 | 0 | 13.075s |
| `com.example.IpPacketTest` | 2 | 0 | 0 | 0.005s |
| `com.example.TlsSniAndDropbearTest` | 5 | 0 | 0 | 0.008s |
| `com.example.ExampleUnitTest` | 1 | 0 | 0 | 0.001s |
| **Total** | **13** | **0** | **0** | **13.089s** |

### Cobertura Funcional Verificada
1. **Motor Multi-SNI + TLS (`TlsSniAndDropbearTest`):**
   - Rejeição honesta de servidores que apenas aceitam TLS/HTTP sem autorizar encaminhamento de túnel (`HTTP 403` -> `tunnelCompatible = false`).
   - Aceitação exclusiva quando o túnel `HTTP CONNECT` responde `200 Connection Established` e encaminha tráfego fim-a-fim.
2. **Motor SSH Dropbear Exclusivo (`TlsSniAndDropbearTest`):**
   - Construção exata da linha de comando `dbclient` com `-D 127.0.0.1:<port>`, `-W 262144`, `-K <keepAlive>`, `-i <key>` e classificação de erros reais (`Permission denied`, `Connection refused`, `Host key mismatch`).
   - Presença e integridade dos binários `libdropbear.so` para todas as 4 ABIs Android.
3. **Motor de Pacotes TUN IPv4/TCP/UDP (`IpPacketTest`):**
   - Construção, parsing e validação de checksums IPv4, TCP (com opção MSS 1460) e UDP (DNS porta 53).
4. **Interface Retro-Tech & Terminal Dev Termux (`ExampleRobolectricTest`):**
   - Nome da aplicação `G-VPN`, botão `LIGAR/DESLIGAR`, seletores `AUTO` / `MANUAL SNI` / `SSH DROPBEAR`.
   - Adição e persistência em Room de perfis Multi-SNI.
   - Abertura automática da aba especial Termux (`>_ TERMINAL DEV`) apenas após estado `CONNECTED` verificado, exibindo os créditos oficiais (`GYLITO BERNARDO`, `DEV`, `FOI UM DIA NORMAL...`, `GYLITO BERNARDO MAULA`, `DEV MURIBA MC`, `876699965`).
