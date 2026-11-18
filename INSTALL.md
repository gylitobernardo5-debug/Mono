# G-VPN — EXTREME SPEED ENGINE V2
**Desenvolvedor:** Gylito Bernardo Maula  
**Apoio Técnico:** Dev Muriba MC  
**Contacto para Apoio e Desenvolvimento:** 876699965  

---

## 1. Requisitos e Arquitetura
- **Plataforma:** Android 7.0+ (API 24–36)
- **Motor SSH Exclusivo:** Dropbear 2026.94 nativo (`libdropbear.so`) compilado com Android NDK para `arm64-v8a`, `armeabi-v7a`, `x86_64` e `x86`, com encaminhamento dinâmico SOCKS5 (`-D`) sobre `direct-tcpip`, `TCP_NODELAY` e janela de receção de 256 KB.
- **Motor Multi-SNI + TLS:** Negociação TLS real com `SNIHostName` no ClientHello, validação de certificado X.509 e verificação obrigatória de encaminhamento real de túnel (`HTTP CONNECT`, `SOCKS5/TLS`, `WebSocket Relay`) com teste HTTP fim-a-fim antes de declarar ligação ativa.
- **Descoberta Pública Gratuita:** Consulta diretórios públicos gratuitos e testa cada candidato ao vivo, apresentando estado real mesmo quando nenhum destino autoriza túnel.

---

## 2. Instalação do APK no Android
1. Transfira o ficheiro compilado `app/build/outputs/apk/debug/app-debug.apk` (ou o APK de lançamento assinado) para o seu dispositivo Android.
2. No telemóvel, abra o gestor de ficheiros, toque em `app-debug.apk` e permita a instalação de fontes desconhecidas para o instalador.
3. Abra a aplicação **G-VPN**.
4. Na primeira ligação, aceite o pedido do sistema Android para criar uma ligação VPN (`VpnService`) e permitir notificações de estado em segundo plano.

---

## 3. Como Utilizar os 3 Modos
1. **MODO AUTO (`TLS / MULTI-SNI`):**
   - Adicione perfis SNI ou clique em **Descoberta Pública**.
   - Selecione **AUTO** e toque em **LIGAR**. O motor testa os perfis elegíveis e escolhe o mais rápido com túnel real confirmado.
2. **MODO MANUAL SNI (`TLS / MULTI-SNI`):**
   - Toque num perfil SNI guardado para o selecionar e toque em **LIGAR**.
   - Pode validar antecipadamente com o botão **Testar TLS + Túnel**.
3. **MODO SSH DROPBEAR (`SSH / DROPBEAR`):**
   - Selecione a aba **SSH / DROPBEAR**, preencha Host, Porta, Utilizador, Palavra-passe ou Chave Privada (ou importe um ficheiro de chave), configure KeepAlive e guarde o perfil.
   - Toque em **LIGAR**. O Dropbear nativo autentica a sessão e encaminha o tráfego TUN real.

---

## 4. Recompilação a partir do Código-Fonte
```bash
# 1. (Opcional) Recompilar Dropbear nativo para todas as ABIs Android:
bash native-dropbear/build_dropbear_android.sh

# 2. Executar testes unitários e Robolectric:
gradle :app:testDebugUnitTest

# 3. Compilar APK instalável:
gradle :app:assembleDebug
```
