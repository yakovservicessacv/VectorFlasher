# Vector Flasher

App Android que se empareja con un robot **Anki Vector** por Bluetooth LE y le
instala firmware, dejándote elegir qué versión quieres.

Las apps oficiales te guían por la configuración pero nunca te dejan elegir el
firmware. Las herramientas web de la comunidad sí, pero necesitan Chrome de
escritorio y Web Bluetooth. Esta lo hace desde el celular, sin cuenta.

No es oficial. No tiene relación con Anki ni con Digital Dream Labs.

---

## Qué hace

- Empareja con Vector por BLE hablando su protocolo **RTS** (versiones 2 a 6)
- Lista firmware de varios espejos y fusiona lo que conteste cada uno
- Estado del robot: número de serie, firmware, batería, Wi-Fi
- Escanea y conecta redes Wi-Fi a través del robot
- Descarga firmware al celular y se lo sirve por la red local, así se puede
  instalar **sin nada de internet**
- Comprueba que el archivo descargado es de verdad un OTA antes de flashear
- Español, inglés, portugués, francés y alemán

## Compilar

```bash
./gradlew assembleDebug      # APK de pruebas
./gradlew assembleRelease    # APK sin firmar
./gradlew bundleRelease      # bundle para Play Store
./gradlew testDebugUnitTest  # 59 tests
```

Hace falta JDK 17 y el SDK de Android 34.

---

## El protocolo, que es lo que costó

Reimplementado en Kotlin leyendo el cliente oficial
([vector-web-setup](https://github.com/digital-dream-labs/vector-web-setup), MIT).

### BLE

| | |
|---|---|
| Servicio | `fee3` |
| Escritura (app → robot) | `7d2a4bda-d29b-4152-b725-2491478c5cd7` |
| Notify (robot → app) | `30619f2d-0f54-41bd-a65a-7588d8c85b45` |
| Tamaño de paquete | 20 bytes |

> En el cliente original esas dos variables se llaman al revés de como se usan.
> Copiarlas por el nombre deja una app que no funciona.

### Framing

Cada paquete es 1 byte de cabecera + payload. Bits 7–6 el estado, bits 5–0 el
tamaño: `0b10` START, `0b00` CONTINUE, `0b01` END, `0b11` SOLO.

### Sobre

```
[0x04][versión RTS 2..6][tag][payload]
```

El handshake es la excepción: `[0x01][versión uint32 LE]`, 5 bytes.

### Emparejamiento

```
robot → handshake
app   → mismo handshake
robot → RtsConnRequest(clave pública)
app   → RtsConnResponse(FirstTimePair, clave pública)   ← Vector muestra el PIN
robot → RtsNonceMessage(toRobot[24], toDevice[24])
app   → RtsAck(RtsNonceMessage)                         ← SIN CIFRAR
                                                        ← desde aquí, cifrado
robot → RtsChallengeMessage(n)
app   → RtsChallengeMessage(n+1)
robot → RtsChallengeSuccessMessage
```

Ese ACK va en claro: en el cliente original el `send()` ocurre antes de
`this.encrypted = true`.

### Cripto

```
kx               = crypto_kx_client_session_keys(clientPk, clientSk, serverPk)
clave descifrado = BLAKE2b-256(mensaje = kx.rx, clave = PIN)
clave cifrado    = BLAKE2b-256(mensaje = kx.tx, clave = PIN)
tráfico          = XChaCha20-Poly1305-IETF, nonce +1 little-endian por operación
```

El PIN no se transmite: es la clave del BLAKE2b. Por eso un PIN mal tecleado no
da "PIN incorrecto", simplemente falla el descifrado.

### Detalles que rompen implementaciones

- El **SSID va en hexadecimal y en MAYÚSCULAS**. En minúsculas, Vector no
  encuentra la red y conectar no hace nada.
- En `RtsWifiConnectRequest`, `hidden` va **siempre en false**, aunque el
  escaneo diga otra cosa.
- `RtsOtaUpdateRequest` es `[longitud uint8][url]`: máximo 255 caracteres.
- En `RtsOtaUpdateResponse`, **status 1 no es un error**, es "aún no arranca".
  Status 2 es descargando y 3 terminado.
- El `expected` de ese mensaje es la imagen **descomprimida** (~823 MB), no el
  tamaño del `.ota`.
- `RtsWifiScanResult` no trae `provisioned` en RTS v2; sí en v3 y posteriores.
- En Android, `characteristic.value` devuelve el array **interno reutilizado**.
  Sin copiarlo, una ráfaga larga se corrompe.

### Formato .ota

Un `.ota` de Vector es un **tar cuyo primer miembro es `manifest.ini`**.

---

## Créditos

- Protocolo: [vector-web-setup](https://github.com/digital-dream-labs/vector-web-setup),
  Digital Dream Labs, licencia MIT
- Ilustraciones de la guía: del mismo repositorio (MIT)
- Iconos: [Font Awesome](https://fontawesome.com/license) Free, CC BY 4.0
- Firmware: espejos de la comunidad (`websetup.skittle.dev`, `ota.pvic.xyz`)

## Licencia

MIT. Ver [LICENSE](LICENSE).
