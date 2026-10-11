# PvP Duels - Parte 1 (primera prueba)

Esta es la base del mod para Minecraft 26.2 con Fabric. Todavia NO tiene duelos ni arenas:
esta parte sirve para comprobar que todo compila y para probar kits y el ordenador del hotbar.

## Que incluye
- `/kit save <nombre>`  guarda tu inventario como kit (se guardan en la carpeta `.minecraft/pvpduels`, no dentro del mundo)
- `/kit load <nombre>`, `/kit list`, `/kit delete <nombre>`
- `/kit profile <kit> <perfil|none>`  hace que ese kit se ordene con un perfil de layout al cargarlo
- Layouts ordenan TODO el inventario: slots 1-9 hotbar, 10-36 mochila (izquierda a derecha, arriba a abajo)
- `/layout slot <1-36> <reglas...>`  hasta 32 niveles de prioridad por slot. Ejemplo: `/layout slot 1 sword spear`
  (reglas: categorias como sword, spear, axe, mace, bow, crossbow, shield, totem, crystal, obsidian,
  gapple, pearl, potion, food, block... o un item exacto, o `hand` para el item que tenes en la mano)
- `/layout level <slot> <1-32> <regla|none>`  cambia un solo nivel
- `/layout inventorycopy [layout] [prioridad]` (tambien `/layout sort inventorycopy ...`) copia lo que tenes en el inventario (hotbar + mochila)
  al layout como items exactos en ese nivel de prioridad. Sin layout usa el activo; sin prioridad usa el proximo nivel libre.
  Asi podes meter varios kits en un solo layout: prioridad 1 = kit A, 2 = kit B, 3 = kit C...
- `/layout use <1-21|nombre>` cambia de layout (los numeros 1 a 21 se crean solos), `/layout copy <de> <a>`, `/layout new <nombre>`,
  `/layout show`, `/layout list`, `/layout clear <slot|all>`, `/layout delete <nombre>` (maximo 21 layouts)
- `/layout sort` o la tecla **Sort inventory** (R por defecto)
- 3 teclas **Quick layout 1/2/3**: cada una cambia a un layout y ordena (por defecto usan los layouts 1, 2 y 3;
  se cambia con `/layout key <1-3> <layout>`, se ve con `/layout keys`). Tambien **Next/Previous layout**
- Todas las teclas se cambian en Opciones > Controles > Combinaciones de teclas > PvP Duels
- `/guide`  muestra los comandos en el chat

## Como conseguir el .jar (GitHub arma el archivo por vos, no instalas nada)
1. Crea una cuenta gratis en https://github.com
2. Arriba a la derecha: **+** > **New repository**. Nombre: `pvpduels`. Dejalo en **Public**. **Create repository**.
3. En la pagina del repositorio nuevo, click en **uploading an existing file**.
4. Descomprimi este zip y arrastra **todo el contenido de la carpeta** (no el zip) a la pagina,
   incluida la carpeta `.github`. Abajo: **Commit changes**.
5. Entra a la pestana **Actions**. Hay un trabajo llamado **build** que arranca solo (tarda unos 3 a 8 minutos).
6. Cuando termine con un tilde verde, abrilo y abajo de todo, en **Artifacts**, descarga `pvpduels-jar`.
   Adentro del zip esta el `.jar` (usa el que NO dice `-sources`).

### Si algo sale mal
- **Cruz roja en Actions:** abri el trabajo, entra a **build**, copia las lineas que dicen `error:` y pegamelas en el chat.
  Es normal que haya que arreglar cosas: no pude compilar el mod en mi entorno.
- **No aparece ningun trabajo en Actions:** probablemente no se subio la carpeta `.github`. Anda a **Add file > Create new file**,
  en el nombre escribi `.github/workflows/build.yml` y pega el contenido que esta al final de este README. **Commit changes**.

## Instalar el mod
- Todos necesitan: Minecraft 26.2, Fabric Loader, **Fabric API**, Essential y este mismo `.jar` en la carpeta `mods`.
- Todos con exactamente la misma version del `.jar`.

## Que probar primero
1. Entra a un mundo. Tiene que aparecer el mensaje "PvP Duels is active".
2. `/guide`
3. Arma un inventario, `/kit save prueba`, tira todo, `/kit load prueba`.
4. `/layout slot 1 sword spear`, `/layout slot 2 totem`, desordena el inventario y apreta la tecla R.

## build.yml (por si hay que crearlo a mano)
```yaml
name: build
on:
  push:
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 25
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: 9.5.1
      - name: Build
        run: gradle build --no-daemon
      - name: Upload the mod
        uses: actions/upload-artifact@v4
        with:
          name: pvpduels-jar
          path: build/libs/pvpduels-*.jar
```
