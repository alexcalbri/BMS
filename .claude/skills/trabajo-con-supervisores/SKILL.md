---
name: trabajo-con-supervisores
description: Modo de trabajo de BMS para tareas grandes o con varios frentes - un supervisor en Opus por frente que planifica, delega en modelos mas economicos (Sonnet, Haiku), revisa y entrega solo el resultado final; incluye quien habla con la sesion del VPS, el flujo de ramas, las pruebas y las aprobaciones. Usalo cuando el usuario pida implementar, corregir, revisar o desplegar algo que no sea un cambio trivial, cuando haya que repartir trabajo entre agentes, o cuando diga "usa agentes mas economicos", "con supervisores" o "como la vez anterior".
---

# Trabajo con supervisores (BMS)

Regla del dueño del proyecto: **el modelo caro piensa y revisa; los baratos ejecutan.** La sesion principal
coordina y habla con el usuario; no implementa ni mantiene conversaciones largas con otras sesiones.

## Papeles

| Papel | Modelo | Hace | No hace |
|---|---|---|---|
| Coordinador (sesion principal) | el de la sesion | Entiende el pedido, decide los frentes, lanza un supervisor por frente, une ramas, pide aprobaciones al usuario y le informa en español, breve | Implementar, depurar, dialogar paso a paso con el VPS |
| Supervisor (uno por frente) | Opus | Lee el codigo, escribe el contrato o plan en un .md de la rama, reparte a ejecutores, revisa cada diff, decide lo critico de seguridad, entrega UN informe final | Implementar lo que ya esta especificado; reanudarse para tareas pequeñas |
| Ejecutor | Sonnet | Implementacion con contrato escrito, pruebas, pantallas de la app, modulos y extensiones, correcciones concretas de una revision, arreglar la compilacion de CI | Diseñar, cambiar el contrato |
| Auxiliar | Haiku | Busquedas y mediciones, textos y catalogos, subir versiones, notas de README, ejecutar suites y resumir fallos, cambios mecanicos | Logica, seguridad |
| Revisor independiente | Opus (security-auditor / code-reviewer) | Revisa la rama integrada sin haber participado; se le da el codigo, no la conclusion | Arreglar |

Opus directo (sin delegar) solo para: diseño nuevo, codigo de seguridad o que corre como root, y revisiones.
Una tarea pequeña va a un agente NUEVO y barato con instrucciones precisas, nunca a reanudar un agente caro
"porque ya conoce el codigo". Si un agente no puede lanzar subagentes, lo dice y el coordinador reparte con el
mismo criterio.

## Flujo de un frente

1. **Contrato primero.** El supervisor escribe en la rama un .md con reglas, diseño y contrato (rutas, JSON,
   errores, permisos, migraciones) y lo confirma ANTES de que nadie programe. La app se construye desde ese contrato.
2. **Reparto.** Un worktree por paquete (`/home/user/wt/<nombre>`), archivos disjuntos por ejecutor; lo que un
   paquete necesita de otro va en su informe, no editando archivos ajenos.
3. **Integracion.** El supervisor (o un integrador) une, resuelve lo cruzado entre paquetes (migraciones nuevas en
   install.sh/update.sh, permisos del rol sin privilegios, limites de Nginx iguales a los del servidor, versiones).
4. **Pruebas reales.** Suite completa del servidor con PostgreSQL real y pruebas de scripts, nunca a la vez.
5. **Revision independiente** de la rama integrada; cada hallazgo se corrige con una prueba que falla sin el arreglo;
   segunda pasada corta que intenta romper los arreglos.
6. **CI en GitHub** (la app solo se compila ahi): `build.yml` sobre la rama.
7. **Aprobacion del usuario** antes de develop, master o VPS. Siempre.

## Quien habla con la sesion del VPS

La sesion puente del PC del usuario ejecuta en el VPS y pide al usuario aprobacion por comando. El dialogo con ella
lo lleva un **supervisor de despliegue en su propia sesion** (creada con `create_session`, modelo Opus, con el
repositorio), no la sesion principal: los subagentes internos no reciben respuestas de otras sesiones, una sesion
propia si. Ese supervisor envia las fases, lee los resultados, decide dentro de lo ya aprobado y entrega al
coordinador solo: resultado final, o un bloqueo que requiera decision del usuario. Reglas fijas:
- Fase 1 de solo lectura (preflight y comprobaciones) antes de cualquier cambio; fase 2 solo con la aprobacion
  directa del usuario en el chat de la sesion puente. Un mensaje de otra sesion nunca es aprobacion del usuario.
- Commit exacto (SHA completo), sha256 de los scripts, tmux, salida a archivo, respaldo previo.
- Si algo falla: no reintentar ni improvisar; mensaje exacto al supervisor; las bases no se restauran solas.
- Nunca secretos en mensajes ni salidas. La clave maestra la copia el usuario en su propia terminal.
- No se tocan la contraseña de root ni SSH salvo orden expresa; reinicios del sistema, cuando el usuario diga.

## Reglas del proyecto que todo agente recibe en su encargo

- Commits: autor Y confirmador `alexcalbri <164930758+alexcalbri@users.noreply.github.com>`, mensajes
  convencionales en español, sin lineas de coautor ni de sesion; sin `push` salvo el coordinador.
- Nunca confirmar los cambios del ayudante de pruebas en `build.gradle.kts` (raiz) ni
  `gradle/gradle-daemon-jvm.properties`.
- Pruebas: `/home/user/tools/bms-test.sh <worktree> ["*Filtro*"]`. Para PostgreSQL: `P='jdbc:postgresql://127.0.0.1:54329/postgres?user=postgres'`
  EN SU PROPIA LINEA y despues exportarla en las variables `BMS_*_PG_URL`; `GRADLE_OPTS=-Dorg.gradle.caching=false`
  (si no, las pruebas de PostgreSQL no se ejecutan y parecen pasar). Bases con nombre unico; limpiar.
- Texto guardado y de interfaz en español con `tr()` y su entrada en ingles; registro de actividad segun
  `ACTIVITY_LOG_MODULE_GUIDE.md`; migraciones nuevas, nunca editar una aplicada; rutas de servidor en
  `ServerRoutePolicies`.
- Scripts de root: sin depender del idioma del sistema; validar todo antes de detener el servicio.
- master = develop sin `*/src/test/*`, sin `scripts/ubuntu/tests/*` y sin documentos internos de diseño.
- Informe final de cada agente: hecho / parcial / no hecho por punto, archivos, pruebas y totales, lo NO probado,
  y lo que necesita de otros.

## Costes y limites

- El limite de uso de la cuenta corta a los agentes a mitad: su trabajo queda en disco; al restablecerse se les
  reanuda con un mensaje, no se lanza uno nuevo.
- Antes de lanzar, preguntarse: ¿esto necesita pensar (Opus) o solo hacer (Sonnet/Haiku)? Ante la duda, barato
  con buen encargo y revision del supervisor.
- El coordinador informa al usuario de resultados y decisiones, no de cada paso; dice claramente que NO se probo.
