# Todas tus ideas para PvP Duels

Lista viva: cada vez que terminamos algo lo marco. Al final del proyecto te la entrego completa.
Leyenda: ✅ hecho y probado por la prueba automatica · 🟡 hecho a medias · ⏳ pendiente

## Kits
- ✅ Kits guardados en tu carpeta (no dentro del mundo), `/kit save|load|list|delete`, sin limite practico (tenias pedido ~50 mil)
- ✅ Teclas **Quick kit A/B/C** para cargar 3 kits (`/kit key`), todas cambiables en Controles
- ✅ Un kit puede tener un layout asociado (`/kit profile`), se ordena solo al cargarlo
- ✅ Taller creativo para crear kits: `/kit create` te manda a un mundo vacio con una plataforma de piedra, creativo limitado (no rompe nada)
- ✅ `/enchant` propio (sin yunque): cualquier encantamiento en cualquier item, hasta nivel 255
- ✅ `/infinite <cantidad>`: apilar items que no se apilan (totems, etc.). Pediste maximo 128, pero Minecraft solo deja stacks de hasta 99, asi que el maximo es 99
- ✅ `/publicate <kit>` (publico para todos), `/private <kit> <jugador>` (se lo das a uno sin que pueda compartirlo), `/unpublish`; los demas ven la lista con `/kit public` y copian con `/kit take`. Ojo: un kit regalado se puede usar pero no compartir ni editar con comandos (a mano siempre lo pueden rearmar)

## Ordenador de inventario (layouts)
- ✅ Tecla para ordenar (R), ordena todo el inventario (slots 1-36, nunca armadura ni mano secundaria)
- ✅ Hasta 21 layouts, 32 niveles de prioridad por slot
- ✅ `/layout inventorycopy` copia tu inventario a un layout; varios kits en un solo layout con prioridades 1, 2, 3...
- ✅ 3 teclas Quick layout + Next/Previous layout
- ✅ Auto-ordenador: `/layout auto [nombre]` mira todos tus kits y pone cada item (o categoria) en el slot donde esta en la mayoria
- 🚫 Nada que automatice el offhand/totem (lo consideras trampa, no se toca)

## Duelos
- ✅ `/duel <jugador>` con menus de cofre: kit → arena → version → como se decide → cantidad
- ✅ Desafio en el chat con botones **[ACCEPT]** / **[DENY]**; `/accept [jugador]`, `/duel accept [jugador]`, `/deny`; vence a los 60 s
- ✅ Revancha con `/again`; salir con `/leave`
- ✅ Rondas fijas (hasta 100 o ilimitadas), primero en N puntos, o por tiempo (hasta 30 min, ronda extra si hay empate)
- ✅ Regla de 5 minutos sin contacto: uno al azar se teletransporta al lado del otro, sin quitar vida
- ✅ Los dos juegan con el kit de quien desafia; inventario guardado en disco y devuelto al terminar/desconectar
- ✅ `/kit load` y Quick kit bloqueados durante un duelo
- ✅ Arena plana: bedrock 21x21 sobre el vacio, con o sin paredes, solo se ponen entidades/bloques con entidad (cofres, shulkers, ender chests, cristales...)
- ✅ `/test_dummy <nombre>` (solo admins): jugador falso que acepta todo, para probar solo
- ✅ `/botduel [kit]`: pregunta "Static bot?" (Yes = no se puede mover, No = recibe knockback) y te manda a una arena de bedrock de 100 bloques hacia cada lado con paredes (no cuenta para tus estadisticas)
- ✅ Arena circular: mundo normal, piso plano, circulo de barriers, radio 100/150/200, se resetea sola (5 bloques de tierra, piedra hasta y0, deepslate hasta y-59, bedrock en y-60)
- ✅ Arena sin limites: si te alejas 100 bloques del rival, cuenta de 5 s, perdes la mitad de la vida y te teletransporta al rival
- ✅ Spawn que vos decoras: un admin hace `/setlobby`; al terminar un duelo te manda ahi (con `/lobby return off` volves a donde estabas); `/lobby` te lleva cuando quieras
- ✅ Lobby protegido (`/lobby protect <radio>`: no se rompe, no se pone, no se pega; los admins en creativo pueden construir) y guardado dentro del mundo
- ✅ Mundo de vacio con una isla flotante grande y verde, con pueblito, montañas y cascada, listo para usar (zip en la release `world-lobby`)
- ✅ `/botduel`: el bot sin cliente ahora recibe knockback de verdad (el golpe de un jugador se manda por red al golpeado y el servidor le quitaba el empujon), el duelo arranca despues del click del menu y se reenvia el kit; si el duelo termina porque alguien se fue, el chat dice quien
- ✅ Puntos de cada uno en el sidebar durante el duelo (hoy salen en la barra de arriba y en titulos)

## Lobby: items, NPCs y libro (entrega 1 de la tanda grande)
- ✅ Hotbar del lobby, inamovible mientras estas en el lobby: slot 1 espada **Send Duel** (verde), slot 2 barrier **Leave** (cancela tus pedidos y te lleva al spawn del lobby), slot 8 bloque de pasto **Create+** (menu con pechera de netherite = Create Kit y bloque de pasto = Create Arena), slot 9 libro de guia
- ✅ Lo que tenias en esos 4 slots se guarda en disco y vuelve al salir del lobby, al desconectarte, al morir o si el juego se cierra de golpe
- ✅ 3 NPCs (muñecos que se clickean): Practice Bot, Kit Workshop y Arena Builder; `/npc add bot|kit|arena`, `/npc remove`, `/npc list` (admins)
- ✅ Libro con indice clickeable, una pagina en blanco entre cada tema y una parte "About this server"
- 🟡 Create Arena: por ahora solo explica que viene (entrega 3: `/createarena`, rango Builder)
- ⏳ La barrier no puede usarse siendo espectador (Minecraft no deja usar items en espectador): ahi se sale con `/leave` o el boton del chat

## Interfaz y ayuda
- ✅ Libro al aparecer con todos los comandos y todo lo que tiene el mod (`/guide book`; el chat sigue con `/guide`)
- ✅ Sidebar a la derecha, desactivable (`/sidebar`), con tus estadisticas y los puntos del duelo
- ✅ Todo el texto del mod en ingles (el unico que sabe español de tu grupo)

## Ideas extra que te gustaron
- ✅ Estadisticas guardadas (victorias, derrotas, rondas...)
- ✅ Reglas por duelo (regeneracion, dano de caida, hambre, construir), `/spectate`, proteccion de 5 s al volver, lobby (`/setlobby`, `/lobby`)
- 🟡 Funcionar bien con Essential: todo corre en la PC del host y el mod no manda nada propio a los amigos, asi que **no hace falta un mod de apoyo**; los amigos solo necesitan el `.jar` para las teclas. Probado en un servidor de prueba, no con Essential de verdad (eso lo tenes que probar vos)

## Principios que pediste
- Todo el texto en ingles · todas las teclas cambiables en Controles · nada que sea trampa · entregar el mod siempre como archivo `.jar` listo para usar

## Propuestas mias (todavia no pedidas, para que elijas)
- ✅ Bot que pelea de vuelta (se acerca y pega) con niveles de dificultad: Passive, Easy, Normal, Hard
- ✅ Contador de practica en `/botduel`: golpes, dano, combo mas largo, por ronda
- ✅ `/top`: ranking por Elo, victorias o racha
- ✅ `/duels`: lista de duelos en curso con boton [WATCH] para mirar
- ✅ Resumen despues de cada duelo (cuanto duro, golpes, dano, combo mas largo, totems, perlas, manzanas de oro y pociones de cada uno) y boton [REMATCH]
- ⏳ Torneos / cola para el grupo de amigos (brackets automaticos, 2v2)
- ⏳ Cola `/queue`: publica (kit publico o al azar), random (kit al azar) y kit propio (los dos pelean con uno tuyo) — entrega 2
- ⏳ Ranking por kit — entrega 2
- ⏳ Arenas propias con `/createarena` (plantillas), `/savearena`, `/setspawn 1|2`, rango Builder por concurso semanal de construccion (dura 3 semanas) y zona de practica — entrega 3
- ⏳ Logros y titulos — entrega 4
- ⏳ 2v2 / equipos — entrega 5 (la ultima, toca el nucleo de los duelos)
