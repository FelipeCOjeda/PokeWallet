# Origem destes .proto

Copiados sem modificação de `setavenger/blindbit-rs`, branch `master`,
commit `b3dc8f6bd03d920856daf3eea6a248c23f171f06` (2026-08-17):
- https://github.com/setavenger/blindbit-rs/blob/master/blindbit-lib/proto/oracle_service.proto
- https://github.com/setavenger/blindbit-rs/blob/master/blindbit-lib/proto/indexing_server.proto

Definem o serviço gRPC `OracleService` do `setavenger/blindbit-oracle`
(indexador BIP-352 Silent Payments) usado pra escanear recebimento —
ver `internal/server/GRPC.md` no repo `blindbit-oracle` pra documentação
de convenções de bytes (little-endian, tweak de 33 bytes, pubkey
truncada de 8 bytes, etc.).

Se o upstream mudar o `.proto`, re-baixar os dois arquivos inteiros aqui
em vez de editar campo a campo — mantém rastreável contra a fonte.
