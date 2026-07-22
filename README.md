# Geoid — silnik kulistej Ziemi dla moda Tellus

[![build](https://github.com/KrzyszofWPL/1-1-scaled-earth-map-minecraft/actions/workflows/build.yml/badge.svg?branch=claude/minecraft-spherical-engine-2l6jam)](https://github.com/KrzyszofWPL/1-1-scaled-earth-map-minecraft/actions/workflows/build.yml)

Companion mod dla **Fabric**, który zmienia płaską mapę 1:1 generowaną przez **Tellus** w
**zamkniętą kulę ziemską**. Realizuje dwa założenia z zadania:

1. **Globalna cyrkumnawigacja** — idąc stale w jednym kierunku wzdłuż wielkiego koła, po przejściu
   pełnego obwodu Ziemi płynnie wracasz do punktu wyjścia (seamless coordinate folding + wrapping
   chunków na szwie antymerydianu i biegunach). **Domyślnie wyłączone** (`enableCircumnavigation =
   false`) — tym zajmuje się [Immersive Portals](#opcjonalna-integracja-z-immersive-portals-naprawdę-widoczny-szew)
   swoimi portalami zawijającymi, więc Geoid nie dubluje tego własnym niewidocznym teleportem. Włącz
   z powrotem (`/geoid circumnavigation true`) tylko na serwerach bez Immersive Portals.
2. **Grawitacja sferyczna i kopanie do antypodów** — grawitacja wskazuje na środek kuli, a kopanie
   pionowo w dół prowadzi przez jądro planety, z **płynnym obrotem wektora grawitacji o 180°** w
   środku, i wychodzi na powierzchni w antypodzie. **Zawsze włączone domyślnie** — to jedyna część
   „kulistej Ziemi”, której nie robi żaden inny mod (Immersive Portals daje portale, nie fizykę), więc
   to jest właściwy rdzeń tego moda.

---

## Najpierw uczciwie: czego silnik Minecrafta zrobić się NIE da

Żeby zaprojektować to dobrze, trzeba nazwać twarde ograniczenia — projekt jest zbudowany wokół nich,
a nie wbrew nim:

| Ograniczenie silnika | Konsekwencja | Jak sobie radzimy |
|---|---|---|
| Siatka wokseli jest **płaska i osiowo-równoległa** — nie da się jej zakrzywić | Nie zrobimy dosłownie „chodzenia po kuli” z zakrzywionym horyzontem terenu | Traktujemy kulę jako **rozmaitość współrzędnych (atlas kart)** rzutowaną na płaską siatkę; iluzję domykamy manipulacją układem odniesienia gracza i wektorem grawitacji |
| Grawitacja to zaszyty skalar `velocity.y -= 0.08` | Brak natywnej grawitacji kierunkowej | Mixin do `Entity#applyGravity` + własny integrator (`SphericalPhysics`, `GeoidGravity`) |
| Nawet największy legalny wymiar (`min_y=-2032, height=4064`) to ~4064 bloków — a średnica Ziemi to 12,74 mln bloków | Nie da się przejść jądra 1:1 w żadnym pojedynczym wymiarze Minecrafta | **Tunel jądra** (`CoreTunnel`) w skali podwójnej + osobny wymiar `geoid:core` (lity szyb skalny, wysokość dobrana tak, by realny przelot się zmieścił): powłoki powierzchniowe 1:1, wnętrze skompresowane; fizyka liczona z prawdziwego `s`, odtwarzanego co tick z pozycji w tym wymiarze |
| Zwykły Overworld ma `min_y=-64` — od domyślnego poziomu morza (Y=64) w dół jest tylko 128 bloków do bedrocku | Gdyby `coreEntryDepth` (próg wejścia do jądra) był ustawiony na więcej niż ~128, gracz uderzy w bedrock/void Overworldu, zanim `geoid:core` w ogóle się włączy — funkcja martwa mimo że kod działa | `coreEntryDepth` domyślnie **100** (patrz `GeoidConfig`), bezpiecznie poniżej granicy 128 i ponad losową warstwą bedrocku (~Y −59…−64) |
| Obwód Ziemi (~40 mln) > pełny zakres jednej osi | Nie da się „przejść” przez antymerydian po płaskiej mapie | **Floating origin** + fold o dokładnie jeden okres (`WorldFolding`); teren jest okresowy → teleport niewidoczny |
| Kamera zna tylko yaw+pitch | Brak roll horyzontu przy przejściu przez jądro | Mixin do `Camera#update` (`CameraRollMixin`) z interpolacją kwaternionową |

**Kluczowa idea:** cała mapa równoprostokątna 1:1 **mieści się** w jednym wymiarze Minecrafta
(`mapX ∈ ±20,015,087`, `mapZ ∈ ±10,007,543`). „Kulistość” nie polega więc na zmieszczeniu terenu, lecz
na **domknięciu krawędzi w szwy** (antymerydian, bieguny) oraz na **skrócie przez jądro** do antypodów.

---

## Model matematyczny (źródło prawdy)

Wszystko wywodzi się z jednego modelu: geodezja → ECEF (3D kartezjański, środek Ziemi) → lokalna
płaszczyzna styczna ENU. To sprawia, że stwierdzenie „świat jest kulą” cokolwiek znaczy: dwa punkty
odległe na płaskiej mapie, ale sąsiednie przez szew, są sąsiednie także w ECEF; a antypod osiągany
kopaniem to prawdziwa linia prosta przez środek w ECEF.

```
r = R + h
x = r·cosφ·cosλ,  y = r·cosφ·sinλ,  z = r·sinφ           (Ecef.geodeticToEcef)
Up    = (cosφcosλ, cosφsinλ, sinφ)
East  = (-sinλ, cosλ, 0)
North = (-sinφcosλ, -sinφsinλ, cosφ)                       (baza ENU, Ecef)
```

Konwencja osi lokalnych (Minecraft): **+X = wschód, +Y = lokalna góra, +Z = południe** (północ = −Z,
jak w vanilli).

Model grawitacji jednorodnej kuli (`SphereMath.gravityMagnitude`):

```
r ≥ R:  g = g0·(R/r)²     (odwrotny kwadrat nad powierzchnią)
r < R:  g = g0·(r/R)      (liniowo → 0 w środku)
```

Ten jeden wzór daje za darmo obie rzeczy: normalne `-Y` na powierzchni oraz nieważkość w środku i
odwrócenie znaku po drugiej stronie.

---

## Jak działa cyrkumnawigacja (seamless folding)

`WorldFolding` utrzymuje **pływający początek układu** wzdłuż długości geograficznej:

```
trueMapX = minecraftX + windowOffsetX      // windowOffsetX jest zawsze wielokrotnością okresu P = 2πR
```

Gdy gracz idzie na wschód „w nieskończoność”, `trueMapX` rośnie bez ograniczeń, a jego współrzędna X
w Minecrafcie pozostaje w oknie jednego okresu `[-P/2, +P/2)`. Przy każdym przekroczeniu szwu
antymerydianu:

1. do `windowOffsetX` dodajemy `±P`,
2. gracza teleportujemy o `∓P` w X (z zachowaniem pędu i widoku).

Obie zmiany znoszą się w `trueMapX`, więc pozycja geodezyjna jest **idealnie ciągła**. Ponieważ
generacja Tellusa jest czystą funkcją pozycji geodezyjnej, teren o jeden okres dalej jest
**identyczny** — teleport jest niewidoczny, o ile wcześniej doczytamy „mostek szwu”
(`seamBridgeTargets` → `AntipodeChunkLoader`).

**Bieguny** (`foldPole`): szerokość nie może przekroczyć ±90°, więc „przejście przez biegun” to odbicie
Z + obrót długości o 180° + odwrócenie kierunku marszu. Rozmaitość karty na biegunie jest osobliwa
(cały górny brzeg mapy to jeden punkt), dlatego jest to jedyne miejsce z widocznym ograniczeniem
wizualnym — w kodzie oznaczone i obsłużone matematycznie, docelowo z czapą azymutalną.

---

## Opcjonalna integracja z Immersive Portals (naprawdę widoczny szew)

**Domyślnie (`enableCircumnavigation = false`) Geoid w ogóle nie teleportuje gracza na szwie** —
zakładamy, że tym zajmuje się [Immersive Portals](https://github.com/iPortalTeam/ImmersivePortalsMod)
swoją strefą zawijania świata, więc po stronie Geoid nie trzeba pisać żadnego kodu tworzącego portale.
Wystarczy, że operator serwera **raz** wpisze w grze (poziom uprawnień 2, jak przy `/gamerule`):

```
/portal global create_outward_wrapping -20015087 -15000000 20015087 15000000
```

Współrzędne to narożniki prostokąta z `EquirectangularProjection`: X = ±`wrapPeriodX()/2`
(~20 015 087 — dokładny szew antymerydianu) i Z znacznie szerzej niż realny limit biegunów
(~10 007 543), żeby brzegi północ-południe tej strefy nigdy nie zostały fizycznie osiągnięte. Realnie
używane będą więc tylko portale wschód-zachód; Immersive Portals nie ma odpowiednika dla bieguna
(odbicie + obrót o 180°, nie prosta pętla), a ponieważ domyślnie cała cyrkumnawigacja Geoid jest
wyłączona, **na biegunach nie ma żadnego domyślnego zabezpieczenia** — to świadomy kompromis: mod ma
teraz skupiać się na fizyce jądra (patrz niżej), nie na obsłudze krawędzi mapy.

Jeśli wolisz zamiast tego **niewidoczny teleport Geoid** (identyczny teren po obu stronach szwu, więc
gracz niczego nie zauważa) — np. na serwerze bez Immersive Portals — włącz z powrotem
`/geoid circumnavigation true` (albo ustaw `enableCircumnavigation = true` w konfiguracji na starcie).
Wtedy:

- Gdy `ImmersivePortalsSupport.PRESENT` jest `true` (mod wykryty: `FabricLoader.isModLoaded
  ("immersive_portals")`), `GeoidServer` mimo to **nie** teleportuje sam gracza na szwie długości
  geograficznej (zrobiłby to podwójnie — portal już go przeniósł), tylko wykrywa skok pozycji o
  dokładnie jeden okres między tickami (`WorldFolding#reconcileExternalFold`), żeby `windowOffsetX`
  zostało w synchronizacji z tym, co faktycznie zrobił portal.
- Gdy Immersive Portals nie jest obecny, `GeoidServer` sam wykonuje niewidoczny teleport na szwie
  (`WorldFolding#foldLongitude`).
- Biegunowy fold (`WorldFolding#foldPole`) zawsze jest obsługiwany przez Geoid, niezależnie od
  obecności Immersive Portals — ten mod nie ma generycznego odpowiednika dla odbicia na biegunie.

---

## Jak działa kopanie do antypodów (tunel jądra)

`CoreTunnel` modeluje przejście w dwóch skalach jednocześnie:

- **Skala fizyki (uczciwa):** parametr `s ∈ [0, 2R]` wzdłuż prawdziwej średnicy. Grawitacja,
  nieważkość w środku i pozycja geodezyjna liczone są z `s` na liczbach rzeczywistych.
- **Skala renderu (skompresowana):** dwie cienkie powłoki powierzchniowe (prawdziwy teren Tellusa,
  po `SHELL_DEPTH` = 512 bloków) są 1:1, a całe wnętrze płaszcza/jądra jest skompresowane do stałej
  wysokości wizualnej (`sToVisualDepth` / `visualDepthToS`).

**Gdzie fizycznie stoi gracz podczas przejścia — wymiar `geoid:core`.** Minecrafta nie da się rozciągnąć
poza ok. 4064 bloków wysokości w jednym wymiarze (limit formatu chunków), a sama średnica Ziemi to
~12,74 mln bloków — więc przejście przez jądro **nie dzieje się w Overworldzie**. Gdy gracz zejdzie
`coreEntryDepth` bloków (domyślnie 100) poniżej `seaLevelY` — próg liczony od poziomu morza, nie od
tego, gdzie faktycznie zaczął kopać — jest teleportowany (`ServerPlayerEntity#teleport`, bez ekranu
ładowania) do osobnego, dołączonego do moda wymiaru `geoid:core`
(`data/geoid/dimension/core.json` + `dimension_type/core.json`, wysokość `min_y=-2032, height=4064`) —
w pełni skonstruowanego, litego szybu skalnego (deepslate/blackstone, z jaśniejącym pasem glowstone w
połowie drogi), każdy gracz w swojej własnej kolumnie wyliczonej z jego rzeczywistego punktu wejścia
(`lon×1000, lat×1000`), żeby dwie osoby kopiące w różnych miejscach na Ziemi nie zderzyły się pod
ziemią. Realna pozycja `s` jest odtwarzana co tick wprost z Y gracza w tym wymiarze przez
`visualDepthToS` (`SphericalPhysics.advanceTraversal`) — nie integrowana z deltą ruchu, więc kompresja
faktycznie działa: jeden blok wykopany głęboko w środku odpowiada tysiącom bloków prawdziwej średnicy.
Po przekroczeniu progu `DIAMETER - coreEntryDepth` gracz wraca do Overworldu na antypodach, **na
rzeczywistą wysokość terenu Tellusa** odczytaną z heightmapy już wcześniej doczytanego chunka
(`realAntipodalSurface`) — a nie na sztywny poziom morza — żeby nie wylądować zamurowany w górze ani
zawieszony wysoko nad dnem oceanu.

**Obrót o 180°:** po bliższej stronie środek jest pod graczem → grawitacja `-Y` (jak vanilla). Za
środkiem środek jest nad graczem → grawitacja `+Y`. Zamiast zostawić gracza „do góry nogami”,
`frameAt(s)` przez slerp kwaternionowy obraca jego układ odniesienia o 180° w okolicy nieważkiego
środka: „dół” zostaje przedefiniowany w stronę (teraz górnego) środka, horyzont się przewraca, a to
samo kopanie w dół **odczuwane jest jako kopanie w górę** ku niebu antypodów. Dokładnie jak w zadaniu.

---

## Doczytywanie chunków na antypodach bez spadków płynności

`AntipodeChunkLoader` to **budżetowany spiralny preloader**. Zamiast ładować cały dysk zasięgu
renderowania w jednym ticku (co ścięłoby serwer), idzie pierścieniami od środka na zewnątrz i wystawia
maksymalnie `chunkBudgetPerTick` ticketów na tick; faktyczną generację robią asynchronicznie wątki
robocze Minecrafta. Wywoływany:

- przy zbliżaniu do szwu (mostek),
- po przekroczeniu środka jądra (powierzchnia antypodu),
- po foldzie okna.

`AntipodeChunkService` dostarcza realną implementację przez `ServerChunkManager.addTicket` z własnym,
samo-wygasającym `ChunkTicketType` na poziomie 33 (pełna generacja bez tickowania — tanio).

---

## Architektura klas

```
math/         (czysta matematyka, testowalna, bez zależności od MC)
  Vec3, Quat                     — algebra 3D + kwaterniony (slerp grawitacji)
  Geodetic, Ecef                 — geodezja ↔ ECEF ↔ ENU, antypod, wrap długości
  GeoProjection, Equirectangular — atlas kart (rzutowanie sfery na płaską siatkę)
  SphereMath                     — stałe, great-circle, model grawitacji
  GravityField                   — wektor grawitacji (ECEF i lokalny)

world/
  PlayerGeoState                 — autorytatywny stan gracza (geodezja + faza + ramka)
  WorldFolding                   — floating origin, fold długości/biegunów, mostek szwu
  CoreTunnel                     — geodezja/fizyka/render traversu przez jądro
  GeoidServer                    — orkiestracja per-gracz per-tick (spina wszystko)

physics/
  SphericalPhysics               — integrator grawitacji + postęp traversu
  GeoidGravity                   — fasada haka (bezpieczna dla serwera dedykowanego)
  PlayerFrame                    — ramka → roll kamery + remap inputu

chunk/
  AntipodeChunkLoader            — budżetowany spiralny preloader (testowalny)
  AntipodeChunkService           — realny TicketSink na ServerChunkManager

integration/
  TellusBridge (+ Fallback, Bridges) — uzgodnienie projekcji z Tellusem lub fallback

net/  GeoStatePayload            — sync stanu serwer → klient (CustomPayload)
config/ GeoidConfig             — konfiguracja
mixin/ EntityGravityMixin        — przejęcie grawitacji (Entity#applyGravity)
       CameraRollMixin           — roll horyzontu (Camera#update)
GeoidMod / client.GeoidClient    — entrypointy Fabric

resources/data/geoid/
  dimension_type/core.json       — geoid:core, min_y=-2032 height=4064 (max legalna wysokość MC)
  dimension/core.json            — generator typu flat: lity szyb deepslate/blackstone/glowstone
```

---

## Wersje i budowanie

Celowany stack: **Fabric / Yarn, Minecraft 1.21.1, Java 21** (patrz `gradle.properties`) — przypięty
świadomie do 1.21.1, a nie najnowszej łatki 1.21.x, żeby dzielić serwer z Immersive Portals (patrz
`suggests.immersive_portals` w `fabric.mod.json`), którego najnowszy publikowany build deklaruje
wsparcie tylko do 1.21.1. Warstwa
matematyczna i fizyczna (`math/`, `world.CoreTunnel`, `world.PlayerGeoState`, `physics.SphericalPhysics`)
jest niezależna od wersji, wolna od zależności na klasy Minecrafta i objęta testami JUnit
(`./gradlew test`) — w tym `SphericalPhysicsTest`, który przypina dokładnie kompresję szybu jądra
(jeden blok w pobliżu wejścia = jedna jednostka `s`, jeden blok głęboko w środku = tysiące jednostek
`s`). Warstwy dotykające wnętrzności MC (mixiny, tickety chunków, sieć, teleport międzywymiarowy) są
oznaczone komentarzami w miejscach zależnych od mapowań — przy zmianie wersji poprawia się tylko te
punkty, logika zostaje.

**Status builda:** pipeline CI (`.github/workflows/build.yml`) buduje mod na runnerach GitHub Actions
przeciw Minecraft 1.21.1 przez Fabric Loom, przechodzi testy jednostkowe i produkuje `geoid-*.jar`
(artefakt `geoid-jars`) — sprawdź aktualny status pod odznaką na górze tego pliku. **Uwaga:** zielony
build oznacza „kompiluje się, testy przechodzą, jar powstaje" — nie zastępuje testów w żywej grze
(faktyczne zachowanie mixinów w runtime, feel seamless-teleportu i teleportu międzywymiarowego,
preload chunków pod obciążeniem).

`CameraRollMixin` to najbardziej wrażliwy na wersję hak (nazwy pól `Camera`), `EntityGravityMixin`
celuje w `applyGravity()` (potwierdzone dla Yarn 1.21.1), a `GeoidServer#teleportCrossDimension` celuje w
`ServerPlayerEntity#teleport(ServerWorld, double, double, double, float, float)` (ten prostszy,
sprzed-1.21.4 wariant bez zbioru flag/boola na końcu — potwierdzone dla Yarn 1.21.1) — dla
starszych/nowszych wersji to jedyne trzy miejsca do poprawy.

> Uwaga: reflektywne wiązanie z Tellusem (`TellusBridges.tryBindTellus`) jest celowo zaślepione do
> fallbacku, dopóki Tellus nie wystawi stabilnego API projekcji — wtedy podmienia się jedną metodę.
> W praktyce nie blokuje to poprawności: obie strony i tak zakładają tę samą, jedyną sensowną
> projekcję dla mapy 1:1 (equirectangular), a jedyne miejsce, gdzie realny teren Tellusa naprawdę się
> liczy — bezpieczne lądowanie na antypodach — czyta go wprost z heightmapy świata
> (`GeoidServer#realAntipodalSurface`), więc działa niezależnie od tego, czy refleksja kiedykolwiek
> zostanie dopięta.
