# TV das crianças

Um app para Google TV / Android TV que vira a "tela inicial" das crianças: fileiras de
pôsteres só com os títulos que você liberou (Disney+, Apple TV, Netflix, Prime Video).
Cada pôster abre o título direto no app oficial. Um **vigia** traz a TV de volta para essa
tela quando as crianças saem para o catálogo do app, abrem outro título ou outro app, ou
apertam Home.

- **Modo crianças:** desligado por padrão, então a TV funciona normalmente para os adultos.
  Você liga no botão "Ligar modo crianças" antes de passar a TV para eles. Para sair, é
  preciso o PIN, digitado como o PIN do Google TV: ↑ ↓ escolhem uma linha de três números,
  ← OK → digitam um deles, e na tela só aparecem bolinhas.
- **Fileiras:** "Continuar assistindo" (os últimos 6 abertos naquela TV), depois as fileiras
  do `titulos.json` (Séries, Filmes, Documentários…), depois apps livres (ex.: Jellyfin).
- Os títulos que vêm no `titulos.json` são um **exemplo**: a lista de uma família.

## O que você precisa

- Uma TV com **Google TV / Android TV** (testado numa TCL com Android TV 14). Numa TV sem
  Google TV, um Google TV Streamer / Chromecast com Google TV resolve.
- Um computador com **Android SDK** e **JDK 17+**. O jeito mais fácil é instalar o
  [Android Studio](https://developer.android.com/studio), que traz os dois, e apontar
  `JAVA_HOME` para o JDK dele (`<android-studio>/jbr`).
- `adb` no PATH (vem no SDK, em `platform-tools`).

## Instalar

1. **Na TV, modo desenvolvedor:** Configurações → Sistema → Sobre → clique 7 vezes em
   "Build do SO do Android TV".
2. **Depuração por Wi-Fi:** Configurações → Sistema → Opções do desenvolvedor → Depuração
   por Wi-Fi → Ativado. A TV precisa estar no **Wi-Fi** (com cabo de rede não funciona).
3. **Parear uma vez:** na mesma tela, "Parear o dispositivo com um código de pareamento".
   No computador:
   ```sh
   adb pair <ip>:<porta-de-pareamento> <código>
   ```
4. **PIN:** crie `local.properties` na raiz com `kidsTv.pin=1234` (o seu PIN). Sem ele, o PIN
   é `1234`.
5. **Instalar:** com o "Endereço IP e porta" que aparece na tela de Depuração por Wi-Fi
   (essa porta muda quando a TV reinicia):
   ```sh
   ./install.sh <ip>:<porta>
   ```
   O script compila, instala e liga o vigia. O Android não deixa ligar a acessibilidade de
   um app instalado fora da Play Store pelas Configurações, então ele faz isso via `adb`.
   Em TVs **TCL**, ele também libera a permissão `AUTO_START`; sem ela, o sistema da TCL
   não deixa o vigia rodar.

O app aparece na lista de apps da TV como **TV das crianças**.

## Escolher os títulos (`app/src/main/assets/titulos.json`)

```json
{
  "rows": ["Séries", "Filmes", "Documentários"],
  "items": [
    {
      "id": "bluey",
      "title": "Bluey",
      "kind": "series",
      "row": "Séries",
      "app": "disney",
      "link": "https://www.disneyplus.com/browse/entity-fa6973b9-e7cf-49fb-81a2-d4908e4bf694",
      "poster": "https://images.justwatch.com/poster/185497966/s718/bluey.jpg",
      "names": ["Bluey"]
    }
  ],
  "free_apps": [{ "title": "Jellyfin", "package": "org.jellyfin.androidtv", "poster": "" }]
}
```

- `app`: `disney`, `apple`, `netflix` ou `prime`.
- `link`: o endereço do título no site do serviço (é o que abre no app da TV):
  - **Disney+:** `https://www.disneyplus.com/browse/entity-…`
  - **Apple TV:** `https://tv.apple.com/us/show/<nome>/umc.cmc.…` (ou `/movie/…`)
  - **Netflix:** `https://www.netflix.com/watch/<id>`. Use `/watch/`, não `/title/`: o vigia
    da Netflix exige que o vídeo esteja tocando.
  - **Prime Video:** `https://watch.amazon.com/watch?asin=<ASIN>&time=0&territory=US`
- **Achar os links:** o [JustWatch](https://www.justwatch.com) mostra onde cada título está.
  Abra a página do título no site do serviço e copie o endereço. **Confira na TV**: os links
  do JustWatch às vezes erram (o de Bluey abria "Bluey Compilations", e os de Frozen 2 e
  Moana abriam as versões Sing-Along).
- `names`: o que o vigia aceita como "está neste título", ou seja, o nome como aparece na
  página do app (em inglês, nos apps dos EUA). Vale o nome exato ou seguido de vírgula ou
  dois-pontos; nunca "contém": "Bluey Compilations" não passa como Bluey.
- `free_apps`: apps abertos sem vigia, como o Jellyfin, cujo servidor já restringe o que
  as crianças veem.

Depois de editar, rode `./install.sh <ip>:<porta>` de novo.

## O que o vigia consegue ver em cada app

| App | O que ele usa |
|---|---|
| Disney+ | O nome do que está tocando e o texto da tela. Detecta o catálogo pelo menu. |
| Apple TV | O texto da tela (às vezes). Detecta o menu lateral. Telas sem texto: se está tocando, e a tecla Voltar a partir da página da série. |
| Netflix / Prime | Só se está tocando, pausado ou parado. Sair do player leva de volta à tela das crianças. |

**Limitações conhecidas:**
- Na **Netflix e no Prime**, uma criança rápida consegue trocar de título em poucos
  segundos. Combine com o controle por título do perfil infantil da Netflix.
- Na **Apple TV**, abrir uma recomendação dentro da página da série não é detectado.
- Quando a TV **reinicia**, a porta da depuração muda. Reinstalar exige a porta nova.
- O app fica com o PIN dentro do APK: é para segurar crianças, não adultos determinados.

## Ideias (feitas no projeto original, fora desta versão)

- **Tempo de TV:** o vigia já sabe, a cada segundo, qual título está na frente e se está
  tocando. No projeto original, ele grava um registro por título (contando só os segundos
  tocando), guarda num arquivo na TV e envia para um servidor próprio, que mostra minutos
  por dia e os títulos mais vistos. Dá para ligar isso a uma planilha ou a um banco simples.
- **Limite diário** de tempo a partir desse registro.
- **Perfil por criança** ao ligar o modo crianças, cada um com suas fileiras.
