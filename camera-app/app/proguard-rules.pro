# Regras do R8 para o build de release.
# As bibliotecas (CameraX, OkHttp, Material, Credential Manager) já trazem as próprias regras;
# aqui ficam só as exceções que elas pedem na documentação.

# Credential Manager: a implementação do Google Play Services é carregada por reflexão.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** {
  *;
}
