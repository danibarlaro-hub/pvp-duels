# PvP Duels (Fabric, Minecraft 26.2)

Mod de duelos PvP con kits, para jugar con Essential (el mundo corre en la PC del host).
Todo lo que se ve dentro del juego esta en ingles.

## Descargar
El `.jar` siempre actualizado esta aca (lo arma GitHub solo en cada cambio):
https://github.com/danibarlaro-hub/pvp-duels/releases/download/latest/pvpduels-0.1.0.jar

Si una version nueva falla, hay copias fijas que nunca cambian:
- **Ultima**: final de cada duelo 1v1 con camara que se aleja desde el centro de la arena (7 bloques), Domain Expansion con titulo para todos, empates (y choque de dominios), fireworks en fila, efectos O explosion, misiones diarias y semanales, 2 semanas de descanso entre concursos, cola random con kits publicos y personales, parejas (`/party`) y duelos 2v2, logros y titulos, arenas propias para Builders, concurso de construccion, cola `/queue`, ranking por kit, items del lobby, NPCs, `/duels` y resumen despues de cada duelo; probado con mas de 1190 chequeos automaticos: https://github.com/danibarlaro-hub/pvp-duels/releases/download/stable-domain/pvpduels-0.1.0.jar
- Con efectos de victoria y misiones diarias, sin la camara del final (unos 1090 chequeos): https://github.com/danibarlaro-hub/pvp-duels/releases/download/stable-effects/pvpduels-0.1.0.jar
- Sin efectos ni misiones (2v2, logros, arenas, concurso, cola; unos 900 chequeos): https://github.com/danibarlaro-hub/pvp-duels/releases/download/stable-2v2/pvpduels-0.1.0.jar
- Sin 2v2 (logros, arenas, concurso, cola; unos 810 chequeos): https://github.com/danibarlaro-hub/pvp-duels/releases/download/stable-achievements/pvpduels-0.1.0.jar
- Mas viejo: todo el mod con el bot que se levanta despues de un KO sin reiniciar nada, `/kit save` que verifica lo que guardo, `/kit show` para mirar adentro de un kit y aviso si un duelo arranca con un kit vacio, y el Sort que se pone la mejor armadura (sin Curse of Binding), probado con 422 chequeos automaticos: https://github.com/danibarlaro-hub/pvp-duels/releases/download/stable-armor/pvpduels-0.1.0.jar
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
- Mientras estas en el lobby (hay que hacer `/setlobby`; sin proteccion cuenta un radio de 64 bloques) tu hotbar tiene 4 items que **no se pueden mover, tirar ni cambiar**: slot 1 espada **Send Duel** (click derecho = lista de jugadores; click derecho o pegarle a un jugador = desafiarlo), slot 2 barrier **Leave** (te saca de la cola, cancela tus pedidos de duelo y te lleva al spawn del lobby), slot 8 bloque de pasto **Create+** (menu: **Create Kit** = el taller de kits, nuevo o editando uno tuyo; **Create Arena** = el estudio de arenas, solo para Builders) y slot 9 el libro de guia.
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
- **Random** (`/queue random`): el kit es sorpresa. Sale de los **kits publicos** o de los **kits personales** de los dos (los tuyos o los de tu rival).
- **Kit propio** (`/queue own <kit>`): ofreces uno de tus kits y esperas; otro jugador lo ve en `/queue` (o usa `/queue take <jugador>`) y los dos pelean con ese kit.
- `/queue list` muestra quien espera, `/queue leave` o el boton **[LEAVE QUEUE]** o el barrier **Leave** del lobby te sacan. Si te desconectas o entras a un duelo/taller/modo espectador te saca solo. Mientras esperas ves un cartelito con el tiempo.
- **Ranking por kit**: cada kit tiene su propio Elo (todos arrancan en 1000 con cada kit). `/top kit` lista los kits con ranking y `/top kit <nombre> [elo|wins|streak]` muestra los mejores con ese kit. Al terminar un duelo ves cuanto cambio tu Elo del kit. Los kits se distinguen por nombre: dos kits que se llamen igual comparten ranking. Se guarda en `pvpduels/kitstats.json`.

## Arenas propias y rango Builder
- Solo los **Builders** (y los admins) hacen arenas. El rango se gana quedando 1ro, 2do o 3ro en el **concurso semanal de construccion** (mas abajo) y dura 3 semanas; un admin tambien lo puede dar con `/builder give <jugador> [semanas]` (por defecto 3) y sacar con `/builder remove <jugador>`. `/builder` te dice si lo tenes y cuanto te queda.
- `/createarena [void|flat|box]` (o **Create+ > Create Arena**, o el NPC Arena Builder) te lleva a tu parcela de 64x64x64 en creativo con una plantilla para empezar: **void** (vacio), **flat** (piso de pasto) o **box** (caja de bedrock). Podes cambiar todo. Lo que construyas no sale del cubo.
- `/setspawn 1` y `/setspawn 2` marcan donde arranca cada jugador (mira para donde estas mirando), `/savearena <nombre>` la guarda (minimo 4 bloques solidos y los dos spawns) y `/leave` sale del estudio; si salis sin guardar queda un **borrador** (menu > Continue my draft). Si te alejas mucho te trae de vuelta.
- `/arena` lista tus arenas con botones **[EDIT]** y **[PUBLISH]/[UNPUBLISH]**; tambien `/arena edit|delete|publish|unpublish <nombre>` y `/arena public` (las compartidas). **Es como los kits: una arena es privada hasta que su Builder la publica**, y entonces cualquiera la puede elegir.
- Para pelear en una: `/duel <jugador>` > arena > **Custom arena** > elegis una tuya o una publica. Cada ronda la arena vuelve a como la guardaste; caerse al vacio (por debajo del cubo) pierde la ronda.
- Limites: los bloques con entidad (cofres, carteles...) no se guardan; los nombres se pasan a minusculas; maximo 30 arenas por jugador.

## Concurso de construccion (`/contest`)
- Arranca solo: sale una **categoria al azar** (castillo, barco pirata, faro, volcan...) y **todos** pueden construirla, no hace falta ser Builder. **5 dias para construir y 2 para votar.** Cuando termina hay **2 semanas de descanso** (`/contest` te dice cuanto falta) y despues empieza el siguiente.
- `/contest` muestra la categoria, cuanto falta y tus botones. `/contest enter` te lleva a tu parcela de 64x64x64 en creativo (tus cosas vuelven al salir), `/contest save` guarda sin salir y `/leave` sale. Lo que construiste queda como tu **entrada** (hay que cambiar al menos 20 bloques de la plantilla) y podes volver a mejorarla hasta que se acabe el tiempo. Si seguis adentro cuando termina, tu obra se guarda igual.
- Al terminar de construir, se arma una **galeria sin nombres**: `/contest vote` abre un menu con "Build #1, #2...". Click en una para visitarla (volas pero no podes tocar nada), con botones **[VOTE]**, **[<]**, **[>]**, **[GALLERY]** y **[LEAVE]**. Un voto por persona, lo podes cambiar hasta que se cierre y no se puede votar a uno mismo.
- Los **3 mas votados** (con al menos 1 voto) son **Builders por 3 semanas** (se suman a lo que ya tengan). Si hay menos de 2 obras se cancela y arranca otro enseguida (sin descanso). `/contest results` muestra el ultimo resultado y al entrar al juego te avisa si ganaste.
- Admins: `/contest start [categoria]` (arranca uno nuevo con la categoria que quieras, tirando el que estaba), `/contest end` (salta a la siguiente fase: de construir a votar, de votar a los resultados, y del descanso al concurso nuevo). Tambien esta en **Create+** (el cuadro).

## Logros y titulos
- `/achievements` abre la lista de **40 logros** con tu progreso (los que no tenes salen grises con "Progress: 3/10"). Se ganan jugando: duelos ganados (1, 10, 25, 50, 100), rachas (3, 5, 10), **Flawless** (ganar sin perder ninguna ronda), Elo (1100, 1200, 1400), combos (5 y 10 golpes), totems, perlas y manzanas doradas usadas, ganar con 3 o 10 kits distintos, jugar en 4 tipos de arena, ganar desde la cola, mirar duelos, KOs al bot de practica (10 y 100), guardar y compartir kits, guardar y compartir arenas, el concurso (participar, votar, quedar en el podio, ganar) y el 2v2 (ganar y los "clutch").
- Cuando desbloqueas uno te avisa unos 3 segundos despues (en el chat y arriba de la hotbar) con un boton **[WEAR]** si trae titulo.
- **Titulos**: `/titles` abre los que ganaste (Rookie, Duelist, Gladiator, Champion, Legend, Streaker, On Fire, Unstoppable, Flawless, Untouchable, Rising Star, Expert, Master, Combo King, Versatile, Traveler, Matchmaker, Crusher, Collector, Generous, Architect, Critic, Builder, Master Builder, Teammate, Duo, Clutch Master). Click en uno y queda **delante de tu nombre** (chat, lista de jugadores y arriba de la cabeza) con un color segun la categoria. `/titles off` lo saca.
- `/profile [jugador]` muestra el Elo, victorias, derrotas, mejor racha, titulo, logros y rango Builder.
- El titulo usa un equipo del marcador (scoreboard team) por cada titulo; si tenes tu propio equipo, el titulo lo reemplaza mientras lo llevas puesto.

## Parejas y 2v2 (`/party`)
- `/party invite <jugador>` le pide a un amigo que sea tu pareja. El otro hace click en **[ACCEPT]** (o `/party accept`, `/party deny`); la invitacion vence a los 60 segundos. Una pareja son **exactamente dos** jugadores. `/party` muestra la tuya y `/party leave` la termina para los dos (no se puede en medio de un duelo). Si alguno se desconecta, la pareja se termina.
- Cuando los dos estan en una pareja, `/duel <jugador>` desafia a ese jugador **y a su pareja**: son los mismos menus (kit, arena, version, modo, cantidad) y los cuatro pelean en la misma arena con el kit de quien desafia. Una pareja solo se puede duelar contra otra pareja, y un jugador solo no puede duelar a una pareja (te avisa por que).
- Cada equipo arranca en su lado con los dos companeros juntos. **No hay fuego amigo.** Si te sacan, quedas en espectador hasta que termine la ronda; **la ronda la gana el equipo que deja afuera a los dos rivales**. Los modos (rondas, primero en N, por tiempo, ilimitado) funcionan igual que en 1v1, y el puntaje cuenta rondas por equipo.
- `/leave` en medio de un 2v2 hace que **tu equipo pierda**. No cambia el Elo ni las estadisticas 1v1; da los logros **Better Together**, **Dynamic Duo**, **Clutch** (ganar una ronda solo contra los dos rivales cuando tu companero ya cayo) y **Clutch Master**.
- Por ahora los 2v2 no se pueden mirar (`/spectate`), no hay cola ni revancha. La cola `/queue` es solo 1v1, asi que no se usa mientras estas en una pareja. `/duels` los lista.

## Efectos de victoria, final del duelo y misiones (`/effects`, `/missions`)
- **Cuando ganas un duelo** (1v1 o 2v2) salen tus efectos: **3 fireworks en fila atras tuyo** (el del medio un bloque mas arriba, y uno a cada lado, a 2,5 bloques) que vuelan al cielo, tus **particulas** durante 5 segundos y, si tenes una, tu **explosion**. Empieza cuando el duelo ya esta decidido, asi que nunca molesta en la pelea, y es solo luz y sonido: no rompe ni lastima nada.
- **Efectos O explosion, no las dos**: si te ponés una explosion se te sacan los fireworks y las particulas, y si te ponés un firework o una particula se te saca la explosion (lo que ganaste no se pierde, solo queda guardado). El menu te avisa cuando pasa.
- `/effects` abre el menu: **Fireworks** (elegis los colores, la forma, la estela y el brillo de cada uno de los 3), **Particles**, **Explosions**, **Missions** (diarias y semanales), **Preview** (para ver tus efectos ahora mismo, tambien `/effects preview`) y un boton para **esconder los efectos de los demas** (no te llegan sus particulas).
- **Fireworks**: son los 16 colores de firework, hasta **5 colores** por firework. Tambien hay 5 formas (small ball, large ball, star, creeper, burst) y dos extras, **trail** y **twinkle**. Arrancas con el blanco en el del medio; el resto se desbloquea con misiones.
- **Particulas**: *todas* las del juego (mas polvo de colores), unas 130. Hay dos lugares: **2 en el piso** (un anillo alrededor tuyo) y **4 en toda la arena** (olas que salen de vos rapido). Si te sale una repetida, **tenes una copia mas** y la podes poner en otro lugar (hasta 6 copias).
- **Explosiones** (muy raras): Orbital Strike Cannon, Orbital Wither Cannon y **Domain Expansion**.

### El final de un duelo 1v1 (camara)
- Cuando un duelo 1v1 termina **la camara se aleja desde el centro de la arena**: arranca en el piso, sube de a poco hasta **7 bloques**, se curva hacia un costado y despues hacia el lado del ganador, y mira siempre al jugador del lado contrario con el otro en cuadro. La ven los dos jugadores **y los que estan mirando con `/spectate`**. Si te molesta, **agachate (sneak) y te lo saltea**. Los jugadores se ven como "dobles" parados en sus posiciones iniciales (mirando al centro), y al terminar cada uno vuelve a donde estaba.
- **Domain Expansion** (si el ganador la tiene puesta): el ganador junta las manos 1,5 segundos (el doble se agacha y le salen particulas), crece un **domo oscuro** sobre toda la arena con el borde de una **linea gris oscura**, y le sale a todos el titulo **"Domain Expansion: I win!"**. Como la Domain Expansion es una explosion, ahi no salen fireworks ni particulas: la explosion los reemplaza.
- **Empates**: un empate **rompe la racha de victorias**. Si los dos tienen efectos normales, o uno tiene Domain Expansion y el otro no, **no pasa nada**, el duelo solo termina. Si los **dos** tienen Domain Expansion hacen un **choque de dominios**: uno rojo y uno azul crecen, se empujan en el medio (separados por la linea gris oscura) hasta que se cansan, sueltan una exhalacion y aparece el titulo **"I guess it's a tie"**.
- Los empates casi no pasan en un duelo real (solo si en una arena ilimitada alguien hace `/leave` con el mismo puntaje), asi que el choque es dificil de ver; se prueba con el self-test.
- El final **alarga un poco el duelo** (unos 7 a 10 segundos). Si algo no deja armar la escena (alguien se desconecta, un forfeit, o un 2v2) pasa el festejo de siempre sin camara. Si te vas con `/leave` en medio del final, recuperas tu cuerpo en el momento.

### Misiones (`/missions`)
- **Diarias**: 6 por dia que se reinician a medianoche: regenerar **100 corazones**, un **combo de 15** en un duelo, **comer 40 comidas**, tomar o tirar **10 pociones**, **ganar 3 duelos seguidos** y una **mision bonus** que cambia todos los dias (jugar 5 duelos, 100 golpes, 5 perlas, 5 manzanas doradas, 10 rondas ganadas, ganar con 2 kits distintos, ganar un 2v2).
- **Semanales**: 5 que se reinician **el lunes**: **ganar 15 duelos**, **ganar con 4 kits distintos**, **ganar 5 seguidos**, **500 golpes** y **ganar 3 duelos 2v2**.
- Cuentan los duelos contra jugadores (1v1 y 2v2), no la practica. **Un empate o una derrota rompen la racha.** Un duelo cuenta para **el dia en que empezo**: si arranca a las 23:59 y termina pasada la medianoche, la mision (y la recompensa) es del dia anterior.
- **Recompensas**: cada mision completada tira los dados: **70%** de que sea algo de firework (un color, forma, estela o brillo que no tenias), **40%** de que sea una particula, y de explosion **0,8%** en una mision diaria y **1,5%** en una semanal (con **garantia a las 100 misiones** sin explosion). Si no sale nada te da una particula igual. Te avisa unos 6 segundos despues del duelo, con un boton **[EFFECTS]**.
- Para probar rapido: un admin hace `/effects unlockall` (o `/effects unlockall <jugador>`) y tenes todo desbloqueado; despues `/effects` y `/effects preview`.
- Las probabilidades estan juntas en `Effects.java` (`fireworkChance`, `particleChance`, `explosionChance`, `weeklyExplosionChance`, `explosionPity`) y el final en `Cinema.java` (con `Cinema.enabled` se apaga la camara). Todo se guarda en `pvpduels/effects.json`.

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
5. Efectos de victoria: un admin hace `/effects unlockall`, despues `/effects` para elegir (fireworks, particulas, explosion) y `/effects preview` para verlos; `/missions` muestra las misiones del dia.

## Prueba automatica
Cada vez que se arma el mod, GitHub levanta un servidor de prueba con dos jugadores falsos que juegan duelos completos
(menus, invitaciones, rondas, vacio, `/leave`, desconexion, reglas de la arena, arenas circular y sin limites, reglas por duelo, `/spectate`, lobby y lobby protegido, taller de kits, `/enchant`, `/infinite`, compartir kits, `/layout auto`, `/test_dummy`, parejas y 2v2 completos (invitaciones, tres rondas, sin fuego amigo, clutch, abandono), `/botduel` con niveles, contadores, Elo y `/top`) y publica el resultado en la rama `selftest-out`.
Lo que esa prueba **no** puede ver: como se dibujan los menus, titulos, marcador y libro en una pantalla real, que tan fluido anda un circulo de radio 200 en tu PC, ni Essential.
