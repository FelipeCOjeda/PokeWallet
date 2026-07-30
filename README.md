# PokéWallet

Uma wallet Bitcoin autocustodial pra Android com visual nostálgico de jogo de
Game Boy da primeira geração (Pokémon Red/Blue/Yellow, anos 90) — incluindo
uma opção de passphrase BIP39 sorteada entre os 151 Pokémon originais.

> ⚠️ **Projeto pessoal/hobby, não auditado.** Veja os avisos em
> [`LICENSE.md`](./LICENSE.md) antes de usar com fundos reais.

## O que é

PokéWallet é uma carteira Bitcoin *self-custodial* (as chaves nunca saem do
aparelho) construída do zero em Kotlin, sem depender de bibliotecas prontas
de carteira — a derivação BIP32/39/84/86, a codificação de endereços
(Bech32/Bech32m), a montagem e assinatura de transações (PSBT, ECDSA e
Schnorr/Taproot) são implementadas diretamente no projeto, usando BouncyCastle
só como biblioteca de curva elíptica de baixo nível.

## Funcionalidades

- **Criação e restauração de wallet** — mnemonic BIP39 de 12 ou 24 palavras.
- **Dois tipos de endereço** — Native SegWit (BIP84, `bc1q…`, padrão) ou
  Taproot (BIP86, `bc1p…`), escolhido na criação/restauração.
- **Passphrase opcional** — sem passphrase, uma personalizada, ou um Pokémon
  sorteado a partir da entropia da própria seed (ex: `pokemon:74:Geodude`) —
  mostrado com o sprite do Pokémon na tela, no estilo Game Boy Red/Blue.
- **Envio e recebimento** — QR code, conversão em tempo real (Sats/BTC/USD/
  BRL), varredura de saldo via API pública (Blockstream).
- **Envio alternativo via BitChat/Nostr** — publica a transação assinada como
  mensagem num canal Nostr público (lido por um bot que faz o broadcast),
  como alternativa a falar direto com uma API centralizada.
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
