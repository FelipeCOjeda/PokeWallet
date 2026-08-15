# PokéWallet

Uma wallet Bitcoin autocustodial pra Android que consegue **enviar
transações sem falar direto com nenhuma API central** — publicando via
BitChat/Nostr — e com visual nostálgico de jogo de Game Boy da primeira
geração (Pokémon Red/Blue/Yellow, anos 90), incluindo uma opção de
passphrase BIP39 sorteada entre os 151 Pokémon originais.

> ⚠️ **Projeto pessoal/hobby, não auditado.** Veja os avisos em
> [`LICENSE.md`](./LICENSE.md) antes de usar com fundos reais.

## O que é

PokéWallet é uma carteira Bitcoin *self-custodial* (as chaves nunca saem do
aparelho) construída do zero em Kotlin, sem depender de bibliotecas prontas
de carteira — a derivação BIP32/39/84/86, a codificação de endereços
(Bech32/Bech32m), a montagem e assinatura de transações (PSBT, ECDSA e
Schnorr/Taproot) são implementadas diretamente no projeto, usando BouncyCastle
só como biblioteca de curva elíptica de baixo nível.

## 📡 Envio offline via BitChat/Nostr — o principal diferencial

Além do envio comum via HTTPS, o PokéWallet consegue transmitir uma
transação assinada **sem fazer nenhuma requisição direta a uma API de
blockchain**: a transação é publicada como uma mensagem num canal Nostr
público (o mesmo formato usado pelo app BitChat), identificada por geohash,
e um bot ouvindo esse canal faz o broadcast de fato pra rede Bitcoin.

Isso significa que:

- Seu IP nunca precisa falar diretamente com uma API centralizada (tipo
  Blockstream/mempool.space) na hora de enviar — só na hora de consultar
  saldo/UTXOs, que é opcional de qualquer forma.
- A identidade Nostr usada em cada envio é **efêmera** — uma chave nova,
  gerada na hora e descartada logo depois, sem nenhuma derivação fixa a
  partir da seed. Não existe uma segunda chave pra guardar ou perder (não
  há nada persistente pra restaurar), e nenhum observador do canal público
  consegue ligar dois envios diferentes à mesma wallet pela identidade
  usada pra publicar.
- Funciona como um plano B de transmissão em cenários de rede restrita ou
  censurada, ou simplesmente pra quem prefere não expor o IP a um serviço
  centralizado no momento do envio.

## 🔒 Privacidade — o que NÃO é escondido

Vale deixar explícito, sem letra miúda: consultar saldo/UTXOs (pra mostrar
quanto você tem, atualizar a tela, calcular o que dá pra enviar) **não**
passa pelo caminho acima — é feito direto, endereço por endereço, contra a
API pública (Blockstream/mempool.space, por padrão) ou contra o node
Electrum próprio que você configurar. Isso significa que:

- Quem estiver do outro lado dessas consultas (o provedor público, ou o
  operador do node Electrum se não for você mesmo) vê seu IP e, ao longo do
  tempo, consegue reconstruir todos os endereços derivados da sua wallet —
  mesmo sem você nunca entregar o xpub literal a ninguém.
- O modo **Tor** (Mochila → 🧅 Configurar Tor, precisa do app Orbot
  instalado) esconde seu IP na hora do **broadcast** de uma transação — não
  nas consultas de saldo, que continuam diretas.
- Se você usa um node Electrum de terceiro (não o seu), ele está na mesma
  posição de qualquer provedor público: vê seus endereços e seu IP.
- Rodar seu próprio node Electrum na sua rede (o cenário pretendido pelo
  suporte a "node próprio" desta wallet) resolve o vazamento de endereços
  pra terceiros, mas continua exigindo confiar nesse node pra não mentir
  saldo — por isso existe o cross-check opcional (Mochila → node Electrum →
  "Cruzar saldo com API pública"), que aceita voltar a falar com a API
  pública só pra conferir divergência, sem forçar isso por padrão.

## Funcionalidades

- **Criação e restauração de wallet** — mnemonic BIP39 de 12 ou 24 palavras.
- **Dois tipos de endereço** — Native SegWit (BIP84, `bc1q…`, padrão) ou
  Taproot (BIP86, `bc1p…`), escolhido na criação/restauração.
- **Passphrase opcional** — sem passphrase, uma personalizada, ou um Pokémon
  sorteado a partir da entropia da própria seed (ex: `pokemon:74:Geodude`) —
  mostrado com o sprite do Pokémon na tela, no estilo Game Boy Red/Blue.
- **Envio e recebimento** — QR code, conversão em tempo real (Sats/BTC/USD/
  BRL), varredura de saldo via API pública (Blockstream).
- **Proteção da seed na tela** — bloqueio de print/gravação de tela enquanto
  o mnemonic ou a passphrase estão visíveis; `wallet.json` local é
  criptografado com AES-256-GCM via Android Keystore.
- **Tema claro/escuro** — visual "tela de Game Boy" (fundo claro, bordas
  duplas, fonte pixelada) com opção de inverter pra fundo preto/fonte branca,
  disponível já na tela inicial.

## Como instalar

Baixe o APK mais recente na aba **[Releases](../../releases)** deste
repositório. É um build de debug (não assinado para a Play Store) — seu
Android vai pedir pra permitir "instalar apps de fontes desconhecidas" na
primeira vez.

## Stack técnica

- Kotlin, Android (`minSdk` 26)
- BouncyCastle (`bcprov-jdk18on`) — só a curva secp256k1, sem biblioteca de
  wallet pronta
- BIP32 (derivação HD), BIP39 (mnemonic), BIP84 (Native SegWit), BIP86
  (Taproot), BIP174 (PSBT), BIP340/341 (Schnorr/Taproot) implementados no
  projeto
- Bech32/Bech32m (BIP173/350) implementado no projeto
- OkHttp pra rede (API Blockstream), ZXing pra QR code, Nostr pro modo de
  envio via BitChat

## Licença

[CC BY-NC-ND 4.0](./LICENSE.md) — uso e compartilhamento não-comercial com
crédito são permitidos; uso comercial ou distribuição de versões modificadas
exigem autorização explícita do autor. Detalhes e contato em
[`LICENSE.md`](./LICENSE.md).
