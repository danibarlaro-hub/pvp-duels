# PvP Duels (Fabric, Minecraft 26.2)

Mod de duelos PvP con kits, para jugar con Essential (el mundo corre en la PC del host).
Todo lo que se ve dentro del juego esta en ingles.

## Descargar
El `.jar` siempre actualizado esta aca (lo arma GitHub solo en cada cambio):
https://github.com/danibarlaro-hub/pvp-duels/releases/download/latest/pvpduels-0.1.0.jar

## Instalar
- Todos los que juegan necesitan: Minecraft 26.2, Fabric Loader, **Fabric API**, Essential y este mismo `.jar` en la carpeta `mods`.
- Todos con exactamente la misma version del `.jar`.
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
- Arena circular y arena ilimitada aparecen como "Coming soon".

## Kits y layouts
- `/kit save|load|list|delete <nombre>` (se guardan en `.minecraft/pvpduels`, no dentro del mundo)
- `/kit key <a|b|c> <kit|none>` asigna un kit a las teclas **Quick kit A / B / C**; `/kit keys` las muestra
- `/kit profile <kit> <layout|none>` ordena ese kit con un layout al cargarlo
- Los layouts ordenan los slots 1-36 (hotbar + mochila): `/layout slot <1-36> <reglas...>` con hasta 32 niveles de prioridad,
  `/layout level`, `/layout inventorycopy [layout] [prioridad]`, `/layout use|copy|new|show|list|clear|delete|sort|key|keys`
- Teclas (Opciones > Controles > PvP Duels): Sort inventory (R), Quick kit A/B/C, Quick layout 1/2/3, Next/Previous layout
- `/guide` muestra los comandos en el chat

## Que probar primero
1. Entra a un mundo: tiene que aparecer "PvP Duels is active".
2. Arma un inventario y `/kit save prueba`.
3. Con un amigo: `/duel nombre`, elegi `prueba`, la arena, y manda el desafio. El otro hace click en [ACCEPT].
4. Probar matar, caerse al vacio, `/leave` y `/again`.

## Prueba automatica
Cada vez que se arma el mod, GitHub levanta un servidor de prueba con dos jugadores falsos que juegan duelos completos
(menus, invitaciones, rondas, vacio, `/leave`, desconexion, reglas de la arena) y publica el resultado en la rama `selftest-out`.
