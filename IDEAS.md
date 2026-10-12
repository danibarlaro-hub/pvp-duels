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
- ⏳ `/publicate <kit>` (publico para todos), `/private <kit> <jugador>` (se lo das a uno sin que pueda compartirlo), `/unpublish`

## Ordenador de inventario (layouts)
- ✅ Tecla para ordenar (R), ordena todo el inventario (slots 1-36, nunca armadura ni mano secundaria)
- ✅ Hasta 21 layouts, 32 niveles de prioridad por slot
- ✅ `/layout inventorycopy` copia tu inventario a un layout; varios kits en un solo layout con prioridades 1, 2, 3...
- ✅ 3 teclas Quick layout + Next/Previous layout
- 🟡 Auto-ordenador que mire todos tus kits y detecte que items van casi siempre en el mismo slot (hoy lo hace `inventorycopy` a mano, sin mirar todos los kits solo)
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
- ⏳ Arena circular: mundo normal, piso plano, circulo de barriers, radio 100/150/200, se resetea sola (5 bloques de tierra, piedra hasta y0, deepslate hasta y-59, bedrock en y-60)
- ⏳ Arena sin limites: si te alejas 100 bloques del rival, cuenta de 5 s, perdes la mitad de la vida y te teletransporta al rival
- ⏳ `/leave` te lleva a un **spawn que vos decoras** (hoy te devuelve a donde estabas antes del duelo; hay que decidir cual preferis)
- ✅ Puntos de cada uno en el sidebar durante el duelo (hoy salen en la barra de arriba y en titulos)

## Interfaz y ayuda
- ✅ Libro al aparecer con todos los comandos y todo lo que tiene el mod (`/guide book`; el chat sigue con `/guide`)
- ✅ Sidebar a la derecha, desactivable (`/sidebar`), con tus estadisticas y los puntos del duelo
- ✅ Todo el texto del mod en ingles (el unico que sabe español de tu grupo)

## Ideas extra que te gustaron
- ✅ Estadisticas guardadas (victorias, derrotas, rondas...)
- ⏳ Reglas por duelo, `/spectate`, proteccion al aparecer y otras extras que te propuse
- ⏳ Funcionar bien con Essential en todos los casos (host en un mundo alterno, etc.) y quizas un mod de apoyo para tus amigos

## Principios que pediste
- Todo el texto en ingles · todas las teclas cambiables en Controles · nada que sea trampa · entregar el mod siempre como archivo `.jar` listo para usar
