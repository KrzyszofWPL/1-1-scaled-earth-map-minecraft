# Geoid — silnik kulistej Ziemi dla moda Tellus

[![build](https://github.com/KrzyszofWPL/1-1-scaled-earth-map-minecraft/actions/workflows/build.yml/badge.svg?branch=claude/minecraft-spherical-engine-2l6jam)](https://github.com/KrzyszofWPL/1-1-scaled-earth-map-minecraft/actions/workflows/build.yml)

Companion mod dla **Fabric**, który zmienia płaską mapę 1:1 generowaną przez **Tellus** w
**zamkniętą kulę ziemską**. Realizuje dwa założenia z zadania:

1. **Globalna cyrkumnawigacja** — idąc stale w jednym kierunku wzdłuż wielkiego koła, po przejściu
   pełnego obwodu Ziemi płynnie wracasz do punktu wyjścia (seamless coordinate folding + wrapping
   chunków na szwie antymerydianu i biegunach).
2. **Grawitacja sferyczna i kopanie do antypodów** — grawitacja wskazuje na środek kuli, a kopanie
   pionowo w dół prowadzi przez jądro planety, z **płynnym obrotem wektora grawitacji o 180°** w
   środku, i wychodzi na powierzchni w antypodzie.

---

## Najpierw uczciwie: czego silnik Minecrafta zrobić się NIE da

Żeby zaprojektować to dobrze, trzeba nazwać twarde ograniczenia — projekt jest zbudowany wokół nich,
a nie wbrew nim:

| Ograniczenie silnika | Konsekwencja | Jak sobie radzimy |
|---|---|---|
| Siatka wokseli jest **płaska i osiowo-równoległa** — nie da się jej zakrzywić | Nie zrobimy dosłownie „chodzenia po kuli” z zakrzywionym horyzontem terenu | Traktujemy kulę jako **rozmaitość współrzędnych (atlas kart)** rzutowaną na płaską siatkę; iluzję domykamy manipulacją układem odniesienia gracza i wektorem grawitacji |
| Grawitacja to zaszyty skalar `velocity.y -= 0.08` | Brak natywnej grawitacji kierunkowej | Mixin do `Entity#applyGravity` + własny integrator (`SphericalPhysics`, `GeoidGravity`) |
| Zasięg współrzędnych ±30 mln, wysokość Y ≈ 384 bloki | Średnica Ziemi (12,74 mln bloków) nie zmieści się w osi Y | **Tunel jądra** (`CoreTunnel`) w skali podwójnej: powłoki powierzchniowe 1:1, wnętrze skompresowane; fizyka liczona z prawdziwego `s` |
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

## Jak działa kopanie do antypodów (tunel jądra)

`CoreTunnel` modeluje przejście w dwóch skalach jednocześnie:

- **Skala fizyki (uczciwa):** parametr `s ∈ [0, 2R]` wzdłuż prawdziwej średnicy. Grawitacja,
  nieważkość w środku i pozycja geodezyjna liczone są z `s` na liczbach rzeczywistych.
- **Skala renderu (skompresowana):** dwie cienkie powłoki powierzchniowe (prawdziwy teren Tellusa,
  po ~512 bloków) są 1:1, a całe wnętrze płaszcza/jądra jest skompresowane do stałej wysokości
  wizualnej (`sToVisualDepth` / `visualDepthToS`).

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
```

---

## Wersje i budowanie

Celowany stack: **Fabric / Yarn, Minecraft 1.21.3, Java 21** (patrz `gradle.properties`). Warstwa
matematyczna jest niezależna od wersji i objęta testami JUnit (`./gradlew test`). Warstwy dotykające
wnętrzności MC (mixiny, tickety chunków, sieć) są oznaczone komentarzami w miejscach zależnych od
mapowań — przy zmianie wersji poprawia się tylko te punkty, logika zostaje.

**Status builda:** pipeline CI (`.github/workflows/build.yml`) buduje mod na runnerach GitHub Actions
i jest **zielony** — `./gradlew build` kompiluje cały kod (w tym oba mixiny i warstwę sieci/chunków)
przeciw Minecraft 1.21.3 przez Fabric Loom, przechodzi testy jednostkowe i produkuje `geoid-*.jar`
(artefakt `geoid-jars`). To potwierdza poprawność mapowań i wersji. **Uwaga:** zielony build oznacza
„kompiluje się, testy przechodzą, jar powstaje" — nie zastępuje testów w żywej grze (faktyczne
zachowanie mixinów w runtime, feel seamless-teleportu, preload chunków pod obciążeniem).

`CameraRollMixin` to najbardziej wrażliwy na wersję hak (nazwy pól `Camera`), a `EntityGravityMixin`
celuje w `applyGravity()` (MC 1.21.3+); dla starszych wersji retarget do `LivingEntity#travel`.

> Uwaga: to jest kompletna architektura i logika referencyjna. Reflektywne wiązanie z Tellusem
> (`TellusBridges.tryBindTellus`) jest celowo zaślepione do fallbacku, dopóki Tellus nie wystawi
> stabilnego API projekcji — wtedy podmienia się jedną metodę.
