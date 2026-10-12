# PvP Duels (Fabric, Minecraft 26.2)

Mod de duelos PvP con kits, para jugar con Essential (el mundo corre en la PC del host).
Todo lo que se ve dentro del juego esta en ingles.

## Descargar
El `.jar` siempre actualizado esta aca (lo arma GitHub solo en cada cambio):
https://github.com/danibarlaro-hub/pvp-duels/releases/download/latest/pvpduels-0.1.0.jar

Si una version nueva falla, hay copias fijas que nunca cambian:
- **Ultima**: todo el mod con `/botduel` con niveles (estatico, passive, easy, normal, hard), contadores de practica, Elo y `/top`, probado con 356 chequeos automaticos: https://github.com/danibarlaro-hub/pvp-duels/releases/download/stable-botlevels/pvpduels-0.1.0.jar
- Todo el mod con `/botduel` basico (bot siempre estatico), probado con 323 chequeos automaticos: https://github.com/danibarlaro-hub/pvp-duels/releases/download/stable-botduel/pvpduels-0.1.0.jar
- Todo el mod sin `/botduel`, probado con 276 chequeos: https://github.com/danibarlaro-hub/pvp-duels/releases/download/stable-full/pvpduels-0.1.0.jar
- Solo hasta los duelos (la primera version probada): https://github.com/danibarlaro-hub/pvp-duels/releases/download/stable-duels/pvpduels-0.1.0.jar

## Instalar
- **El host** (el que abre el mundo con Essential) necesita: Minecraft 26.2, Fabric Loader, **Fabric API**, Essential y este `.jar` en la carpeta `mods`. Todo corre en su PC: los kits, layouts, estadisticas y el lobby se guardan en su `.minecraft/pvpduels`.
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
- No cuenta para tus estadisticas. Si dos amigos hacen `/botduel` a la vez, cada uno tiene su bot y su arena (hasta 3 duelos en total).
- La primera vez que se usa cada arena el juego se traba como un segundo mientras la arma (son 73.000 bloques); despues se limpia en menos de 0,1 s.

## Taller de kits (crear kits en creativo)
- `/kit create [kit]` te manda a un mundo vacio con una plataforma de piedra, en creativo, pero **sin poder romper ni poner nada**. Si das el nombre de un kit, se carga para editarlo.
- `/enchant <encantamiento> [nivel 0-255]` encanta el item de tu mano con cualquier encantamiento en cualquier item (nivel 0 lo saca). Reemplaza al `/enchant` de Minecraft; los admins conservan la forma `/enchant <objetivos> <encantamiento> [nivel]`. Tambien existe `/ench`.
- `/infinite <1-99>` apila items que normalmente no se apilan (totems, etc.). Maximo 99: es el limite de Minecraft para guardar stacks. No funciona con items con durabilidad.
- `/kit save <nombre>` guarda lo que armaste. `/leave` o `/kit exit` te devuelve tu inventario y tu lugar.
- `/enchant` e `/infinite` solo funcionan en el taller (los admins los pueden usar en cualquier lado).

## Reglas, espectadores y lobby
- En el menu de `/duel` hay una pantalla de reglas: regeneracion natural, dano de caida, hambre y construir (en las arenas circular y sin limites), se apagan solo para ese duelo.
- `/spectate <jugador>` (o `/watch`) mira un duelo en modo espectador (se guarda tu inventario como en un duelo); `/leave` para salir. Reemplaza al `/spectate` de Minecraft, que era solo para admins. Al volver de un duelo tenes 5 segundos de proteccion.
- Lobby: un admin se para donde quiere y hace `/setlobby`. Al terminar un duelo vas ahi; `/lobby return off` para volver a donde estabas; `/lobby` te lleva cuando quieras; `/lobby clear` lo borra.
- `/layout auto [nombre]` arma un layout mirando todos tus kits (hasta los 300 mas nuevos): cada item o categoria que esta en el mismo slot en la mayoria de los kits queda en ese slot.

## Compartir kits
- `/publicate <kit>` lo deja en el estante publico; todos lo ven con `/kit public` y lo copian con `[TAKE]` o `/kit take <kit>` (queda como un kit propio).
- `/unpublish <kit>` lo saca del estante (solo el autor o un admin). Las copias que ya se llevaron quedan.
- `/private <kit> <jugador>` le da una copia **bloqueada** a un jugador conectado: la puede usar (`/kit load`, duelos, teclas) pero no puede publicarla, regalarla, editarla en el taller ni pisarla con `/kit save`.
- Limite: si alguien carga el kit y lo vuelve a guardar a mano con otro nombre, el mod no lo puede impedir.

## Sidebar y estadisticas
- A la derecha de la pantalla aparece un marcador solo para vos: fuera de un duelo muestra tus estadisticas (victorias, derrotas, racha, rondas), durante un duelo el puntaje, y en el taller los kits que tenes.
- `/sidebar` lo apaga o prende (se acuerda de tu eleccion). Las estadisticas se guardan en `.minecraft/pvpduels`.
- Los duelos de practica contra `/test_dummy` y contra el bot no cuentan para las estadisticas.
- **Elo y ranking**: todos empiezan con 1000 Elo (K=32, suma cero: lo que gana uno lo pierde el otro). `/top` muestra el ranking por Elo, `/top wins` por victorias y `/top streak` por mejor racha; abajo te dice tu puesto. El Elo tambien aparece en tu marcador y en el mensaje al terminar el duelo.

## Probar solo (admins)
- `/test_dummy <nombre>` invoca un jugador falso a tu lado (tambien `/fakeplayer`). No se mueve, no pelea y acepta todos los duelos y revanchas. `/duel <nombre>` y a pegarle. Se saca con `/test_dummy remove <nombre>`.
- Hace falta tener permisos de admin (en un mundo local: crear el mundo con trucos activados).

## Kits y layouts
- `/kit save|load|list|delete <nombre>` (se guardan en `.minecraft/pvpduels`, no dentro del mundo)
- `/kit key <a|b|c> <kit|none>` asigna un kit a las teclas **Quick kit A / B / C**; `/kit keys` las muestra
- `/kit profile <kit> <layout|none>` ordena ese kit con un layout al cargarlo
- Los layouts ordenan los slots 1-36 (hotbar + mochila): `/layout slot <1-36> <reglas...>` con hasta 32 niveles de prioridad,
  `/layout level`, `/layout inventorycopy [layout] [prioridad]`, `/layout use|copy|new|show|list|clear|delete|sort|key|keys`
- Teclas (Opciones > Controles > PvP Duels): Sort inventory (R), Quick kit A/B/C, Quick layout 1/2/3, Next/Previous layout
- `/guide` muestra los comandos en el chat; `/guide book` te da un libro con toda la ayuda (no se guarda en los kits)

## Que probar primero
1. Entra a un mundo: tiene que aparecer "PvP Duels is active".
2. Arma un inventario y `/kit save prueba`.
3. Con un amigo: `/duel nombre`, elegi `prueba`, la arena, y manda el desafio. El otro hace click en [ACCEPT].
4. Probar matar, caerse al vacio, `/leave` y `/again`.

## Prueba automatica
Cada vez que se arma el mod, GitHub levanta un servidor de prueba con dos jugadores falsos que juegan duelos completos
(menus, invitaciones, rondas, vacio, `/leave`, desconexion, reglas de la arena, arenas circular y sin limites, reglas por duelo, `/spectate`, lobby, taller de kits, `/enchant`, `/infinite`, compartir kits, `/layout auto`, `/test_dummy`, `/botduel` con niveles, contadores, Elo y `/top`) y publica el resultado en la rama `selftest-out`.
Lo que esa prueba **no** puede ver: como se dibujan los menus, titulos, marcador y libro en una pantalla real, que tan fluido anda un circulo de radio 200 en tu PC, ni Essential.
