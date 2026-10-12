# PvP Duels (Fabric, Minecraft 26.2)

Mod de duelos PvP con kits, para jugar con Essential (el mundo corre en la PC del host).
Todo lo que se ve dentro del juego esta en ingles.

## Descargar
El `.jar` siempre actualizado esta aca (lo arma GitHub solo en cada cambio):
https://github.com/danibarlaro-hub/pvp-duels/releases/download/latest/pvpduels-0.1.0.jar

Si una version nueva falla, hay copias fijas que nunca cambian:
- **Ultima**: cola `/queue` (publica, random y kit propio) y ranking por kit (`/top kit <nombre>`), ademas de los items fijos del lobby (Send Duel, Leave, Create+, libro de guia), los 3 NPCs, `/duels` con [WATCH] y el resumen despues de cada duelo; probado con unos 560 chequeos automaticos: https://github.com/danibarlaro-hub/pvp-duels/releases/download/stable-queue/pvpduels-0.1.0.jar
- Con el lobby protegido y el mundo de la isla flotante, 370 chequeos: https://github.com/danibarlaro-hub/pvp-duels/releases/download/stable-lobby/pvpduels-0.1.0.jar
- Todo el mod con `/botduel` con niveles (estatico, passive, easy, normal, hard), contadores de practica, Elo y `/top`, probado con 356 chequeos: https://github.com/danibarlaro-hub/pvp-duels/releases/download/stable-botlevels/pvpduels-0.1.0.jar
- Todo el mod con `/botduel` basico (bot siempre estatico), probado con 323 chequeos automaticos: https://github.com/danibarlaro-hub/pvp-duels/releases/download/stable-botduel/pvpduels-0.1.0.jar
- Todo el mod sin `/botduel`, probado con 276 chequeos: https://github.com/danibarlaro-hub/pvp-duels/releases/download/stable-full/pvpduels-0.1.0.jar
- Solo hasta los duelos (la primera version probada): https://github.com/danibarlaro-hub/pvp-duels/releases/download/stable-duels/pvpduels-0.1.0.jar

## Instalar
- **El host** (el que abre el mundo con Essential) necesita: Minecraft 26.2, Fabric Loader, **Fabric API**, Essential y este `.jar` en la carpeta `mods`. Todo corre en su PC: los kits, layouts y estadisticas se guardan en su `.minecraft/pvpduels`, y el lobby dentro de la carpeta del mundo (asi viaja con el mundo).
- **Los amigos** deberian poder entrar sin el `.jar`: el mod no manda nada propio al cliente (menus de cofre, marcador, libro y botones del chat son de Minecraft). Sin el `.jar` no tienen las **teclas** (ordenar, Quick kit, Quick layout); con los comandos funciona todo igual. Esto no se pudo probar con Essential de verdad, asi que si a alguien le da error al entrar, que ponga el mismo `.jar` y Fabric API.
- Si todos lo tienen instalado, que sea la misma version del `.jar`.
- Para actualizar: borra el `.jar` viejo de `mods`, pone el nuevo y reinicia el juego/mundo.

## Duelos
- `/duel <jugador>` abre menus de cofre: kit -> arena -> version (con/sin paredes) -> como se decide -> cantidad -> enviar desafio.
- El otro jugador recibe el desafio en el chat con botones **[ACCEPT]** / **[DENY]** (tambien `/accept [jugador]`, `/duel accept`, `/deny`). Vence a los 60 segundos.
- Los dos usan el kit de quien desafia. Tu inventario se guarda en disco antes de empezar y se devuelve al terminar, al hacer `/leave`, al desconectarte (cuando vuelvas a entrar) o si se cierra el servidor.
- Modos: **rondas fijas** (1 a 100, o ilimitadas hasta `/leave`), **primero en N puntos** (o ilimitado), **por tiempo** (3 a 30 min; se termina la ronda en curso, desempate con ronda extra).
- Caerse al vacio = perder la ronda. 5 minutos sin pegarse = a uno de los dos lo teletransportan al lado del otro (sin quitar vida).
- Arena: plataforma plana de bedrock 21x21 sobre el vacio, con o sin paredes. Solo se pueden poner entidades y bloques con entidad (cofres, shulkers, cristales, botes...). TNT, agua, lava y fuego no.
- Durante un duelo `/kit load` y las teclas Quick kit estan bloqueadas.
- `/leave` salir del duelo, `/again` pedir revancha con la misma configuracion.
- Hasta 3 duelos al mismo tiempo en el mundo.
- **Arena circular** (radio 100, 150 o 200): terreno plano normal (5 de tierra, piedra hasta y0, deepslate hasta y-59, bedrock en y-60) dentro de un circulo de barreras invisibles. Se puede poner y romper todo; el terreno se resetea solo antes de cada ronda. Salir del circulo = perder la ronda.
- **Arena sin limites**: el mismo terreno sin borde. Si estas a mas de 100 bloques de tu rival durante 5 segundos, el que se alejo (el que esta mas lejos del punto de inicio) pierde la mitad de la vida y se teletransporta al lado del rival.

## Practicar solo contra un bot (`/botduel`)
- `/botduel [kit] [tipo]` abre los menus: elegis el kit (si no lo escribiste) y te pregunta **Static bot?** (¿bot estatico?). Te manda a una arena de bedrock de **100 bloques hacia cada lado** (201x201) con paredes de 40 de alto.
  - **Yes** = bot estatico: no se puede mover (si lo empujan o lo golpean vuelve al lugar) y no pelea de vuelta.
  - **No** = el bot **si se mueve y recibe knockback**, y elegis como se porta: **Passive** (no pelea), **Easy** (se acerca y pega lento, falla mucho), **Normal** (corre hacia vos y pega cada vez que puede) o **Hard** (corre, se mueve de costado y casi no falla).
  - Atajo sin menus: `/botduel <kit> static|passive|easy|normal|hard`.
- El bot se llama `Bot` y tiene tu kit. Aparece en la lista de jugadores como uno mas ("Bot joined the game" en el chat).
- **Contador de practica**: el marcador muestra tus golpes, el dano, tu combo actual y el mejor (dos golpes seguidos con menos de 1 segundo entre ellos son combo) y, si el bot pelea, los golpes que recibiste. Al terminar cada ronda te dice el resumen y al salir con `/leave` el total.
- Las rondas siguen hasta que hagas `/leave` (se juega a puntos sin limite). Cada ronda la arena se limpia sola y el bot vuelve a su lugar. Las reglas de construccion son las de la arena plana (solo entidades y bloques con entidad).
- Si dejas al bot sin vida (KO) se levanta solo con la vida llena, te suma un KO y **no se reinicia nada** (ni la arena, ni tu posicion, ni el kit). La ronda solo se reinicia si te caes al vacio o si el bot te mata a vos.
- No cuenta para tus estadisticas. Si dos amigos hacen `/botduel` a la vez, cada uno tiene su bot y su arena (hasta 3 duelos en total).
- La primera vez que se usa cada arena el juego se traba como un segundo mientras la arma (son 73.000 bloques); despues se limpia en menos de 0,1 s.

## Taller de kits (crear kits en creativo)
- `/kit create [kit]` te manda a un mundo vacio con una plataforma de piedra, en creativo, pero **sin poder romper ni poner nada**. Si das el nombre de un kit, se carga para editarlo.
- `/enchant <encantamiento> [nivel 0-255]` encanta el item de tu mano con cualquier encantamiento en cualquier item (nivel 0 lo saca). Reemplaza al `/enchant` de Minecraft; los admins conservan la forma `/enchant <objetivos> <encantamiento> [nivel]`. Tambien existe `/ench`.
- `/infinite <1-99>` apila items que normalmente no se apilan (totems, etc.). Maximo 99: es el limite de Minecraft para guardar stacks. No funciona con items con durabilidad.
- `/kit save <nombre>` guarda lo que armaste. `/leave` o `/kit exit` te devuelve tu inventario y tu lugar.
- `/enchant` e `/infinite` solo funcionan en el taller (los admins los pueden usar en cualquier lado).

## Lobby: items, NPCs y libro
- Mientras estas en el lobby (hay que hacer `/setlobby`; sin proteccion cuenta un radio de 64 bloques) tu hotbar tiene 4 items que **no se pueden mover, tirar ni cambiar**: slot 1 espada **Send Duel** (click derecho = lista de jugadores; click derecho o pegarle a un jugador = desafiarlo), slot 2 barrier **Leave** (te saca de la cola, cancela tus pedidos de duelo y te lleva al spawn del lobby), slot 8 bloque de pasto **Create+** (menu: **Create Kit** = el taller de kits, nuevo o editando uno tuyo; **Create Arena** = todavia solo avisa) y slot 9 el libro de guia.
- Lo que tenias en esos slots se guarda en disco (`pvpduels/lobbystash`) y vuelve cuando salis del lobby, te vas, moris o se cierra el juego (tambien si se cae de golpe: te lo devuelve al volver a entrar). Los admins en creativo no reciben los items (asi pueden construir).
- Para que no se muevan el mod usa dos mixins chicos (`AbstractContainerMenu.clicked` y `Inventory.removeFromSelected`).
- NPCs: muñecos (mannequins de Minecraft) que no se mueven ni se lastiman. **Practice Bot** abre el menu de `/botduel`, **Kit Workshop** el de crear kits y **Arena Builder** explica lo de las arenas. Los admins los ponen con `/npc add bot|kit|arena` donde estan parados, `/npc remove` borra el mas cercano y `/npc list` los lista.
- El libro tiene un indice con links, una pagina en blanco entre cada tema y al final un poco sobre el servidor.
- `/duels` lista los duelos en curso con un boton **[WATCH]**.
- Despues de cada duelo contra un jugador sale un resumen: cuanto duro y, de cada uno, golpes, dano, combo mas largo y los totems, perlas, manzanas de oro y pociones que uso. Ahi mismo hay un boton **[REMATCH]**.

## Reglas, espectadores y lobby
- En el menu de `/duel` hay una pantalla de reglas: regeneracion natural, dano de caida, hambre y construir (en las arenas circular y sin limites), se apagan solo para ese duelo.
- `/spectate <jugador>` (o `/watch`) mira un duelo en modo espectador (se guarda tu inventario como en un duelo); `/leave` para salir. Reemplaza al `/spectate` de Minecraft, que era solo para admins. Al volver de un duelo tenes 5 segundos de proteccion.
- Lobby: un admin se para donde quiere y hace `/setlobby`. Al terminar un duelo vas ahi; `/lobby return off` para volver a donde estabas; `/lobby` te lleva cuando quieras; `/lobby clear` lo borra. El lobby queda guardado **dentro del mundo** (`pvpduels/lobby.json`).
- Lobby protegido: `/lobby protect <radio>` (admins; por ejemplo 100) hace que dentro de ese radio nadie pueda romper ni poner bloques ni pegarle a otro jugador. Los admins en creativo si pueden construir. `/lobby protect off` lo apaga. Si alguien se cae de la isla, vuelve solo al lobby.

## Mundo con isla flotante (lobby listo)
Un mundo de vacio con una isla flotante grande y verde (pueblito con plaza y fuente, 5 casas y un salon, cartel gigante "PVP DUELS", dos montañas chicas con camino y mirador, rio con cascada, estanques, bosque y flores). Ya viene con el lobby puesto y protegido (radio 105).
1. Descarga `PvP-Duels-Lobby-world.zip` de https://github.com/danibarlaro-hub/pvp-duels/releases/tag/world-lobby y descomprimilo en `.minecraft/saves`.
2. Pone el `.jar` del mod (esta en la misma pagina) en `mods`, junto con Fabric API.
3. Abri el mundo "PvP Duels Lobby" (con Essential: abrilo a LAN/invitar a tus amigos como siempre).
La primera vez que se carga, Minecraft calcula la luz de la isla y puede tardar unos segundos.
- `/layout auto [nombre]` arma un layout mirando todos tus kits (hasta los 300 mas nuevos): cada item o categoria que esta en el mismo slot en la mayoria de los kits queda en ese slot.

## Compartir kits
- `/publicate <kit>` lo deja en el estante publico; todos lo ven con `/kit public` y lo copian con `[TAKE]` o `/kit take <kit>` (queda como un kit propio).
- `/unpublish <kit>` lo saca del estante (solo el autor o un admin). Las copias que ya se llevaron quedan.
- `/private <kit> <jugador>` le da una copia **bloqueada** a un jugador conectado: la puede usar (`/kit load`, duelos, teclas) pero no puede publicarla, regalarla, editarla en el taller ni pisarla con `/kit save`.
- Limite: si alguien carga el kit y lo vuelve a guardar a mano con otro nombre, el mod no lo puede impedir.

## Cola (`/queue`)
- Para jugar sin tener que pedirle a nadie: `/queue` abre un menu con **tres colas**. Cuando hay otro jugador esperando en la misma cola el duelo arranca solo: arena plana con paredes, el primero en llegar a 3 puntos (cuenta para el Elo).
- **Publica** (`/queue public [kit]`): elegis un kit publico (o "Random public kit") y te toca alguien que eligio lo mismo.
- **Random** (`/queue random`): el kit es sorpresa. Sale de los kits publicos, de los tuyos, de los de tu rival o de uno que alguien este ofreciendo en la cola de kit propio.
- **Kit propio** (`/queue own <kit>`): ofreces uno de tus kits y esperas; otro jugador lo ve en `/queue` (o usa `/queue take <jugador>`) y los dos pelean con ese kit.
- `/queue list` muestra quien espera, `/queue leave` o el boton **[LEAVE QUEUE]** o el barrier **Leave** del lobby te sacan. Si te desconectas o entras a un duelo/taller/modo espectador te saca solo. Mientras esperas ves un cartelito con el tiempo.
- **Ranking por kit**: cada kit tiene su propio Elo (todos arrancan en 1000 con cada kit). `/top kit` lista los kits con ranking y `/top kit <nombre> [elo|wins|streak]` muestra los mejores con ese kit. Al terminar un duelo ves cuanto cambio tu Elo del kit. Los kits se distinguen por nombre: dos kits que se llamen igual comparten ranking. Se guarda en `pvpduels/kitstats.json`.

## Sidebar y estadisticas
- A la derecha de la pantalla aparece un marcador solo para vos: fuera de un duelo muestra tus estadisticas (victorias, derrotas, racha, rondas), durante un duelo el puntaje, y en el taller los kits que tenes.
- `/sidebar` lo apaga o prende (se acuerda de tu eleccion). Las estadisticas se guardan en `.minecraft/pvpduels`.
- Los duelos de practica contra `/test_dummy` y contra el bot no cuentan para las estadisticas.
- **Elo y ranking**: todos empiezan con 1000 Elo (K=32, suma cero: lo que gana uno lo pierde el otro). `/top` muestra el ranking por Elo, `/top wins` por victorias y `/top streak` por mejor racha; abajo te dice tu puesto. El Elo tambien aparece en tu marcador y en el mensaje al terminar el duelo.

## Probar solo (admins)
- `/test_dummy <nombre>` invoca un jugador falso a tu lado (tambien `/fakeplayer`). No se mueve, no pelea y acepta todos los duelos y revanchas. `/duel <nombre>` y a pegarle. Se saca con `/test_dummy remove <nombre>`.
- Hace falta tener permisos de admin (en un mundo local: crear el mundo con trucos activados).

## Kits y layouts
- `/kit save|load|list|show|delete <nombre>` (se guardan en `.minecraft/pvpduels`, no dentro del mundo)
- `/kit save` lee el archivo de vuelta y te dice **cuantos items quedaron guardados**; si algo no se pudo guardar igual te avisa slot por slot. Con el inventario vacio **no guarda** (asi un kit bueno nunca se pisa con uno en blanco).
- `/kit show <nombre>` abre el kit como un cofre que solo se puede mirar (armadura arriba a la izquierda, mano secundaria arriba a la derecha, mochila en el medio y barra de acceso abajo).
- `/kit key <a|b|c> <kit|none>` asigna un kit a las teclas **Quick kit A / B / C**; `/kit keys` las muestra
- `/kit profile <kit> <layout|none>` ordena ese kit con un layout al cargarlo
- Los layouts ordenan los slots 1-36 (hotbar + mochila): `/layout slot <1-36> <reglas...>` con hasta 32 niveles de prioridad,
  `/layout level`, `/layout inventorycopy [layout] [prioridad]`, `/layout use|copy|new|show|list|clear|delete|sort|key|keys`
- **Sort tambien se pone la mejor armadura** que lleves en el inventario (la que tiene mas puntos de armadura, dureza, Protection y durabilidad). Nunca se pone una pieza con Curse of Binding ni te saca una que ya la tiene. No toca elytra, calabazas ni cabezas. `/layout armor off|on` lo apaga o prende (prendido por defecto).
- Teclas (Opciones > Controles > PvP Duels): Sort inventory (R), Quick kit A/B/C, Quick layout 1/2/3, Next/Previous layout
- `/guide` muestra los comandos en el chat; `/guide book` te da un libro con toda la ayuda (no se guarda en los kits)

## Que probar primero
1. Entra a un mundo: tiene que aparecer "PvP Duels is active".
2. Arma un inventario y `/kit save prueba`.
3. Con un amigo: `/duel nombre`, elegi `prueba`, la arena, y manda el desafio. El otro hace click en [ACCEPT].
4. Probar matar, caerse al vacio, `/leave` y `/again`.

## Prueba automatica
Cada vez que se arma el mod, GitHub levanta un servidor de prueba con dos jugadores falsos que juegan duelos completos
(menus, invitaciones, rondas, vacio, `/leave`, desconexion, reglas de la arena, arenas circular y sin limites, reglas por duelo, `/spectate`, lobby y lobby protegido, taller de kits, `/enchant`, `/infinite`, compartir kits, `/layout auto`, `/test_dummy`, `/botduel` con niveles, contadores, Elo y `/top`) y publica el resultado en la rama `selftest-out`.
Lo que esa prueba **no** puede ver: como se dibujan los menus, titulos, marcador y libro en una pantalla real, que tan fluido anda un circulo de radio 200 en tu PC, ni Essential.
