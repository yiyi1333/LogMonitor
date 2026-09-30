# LogMonitor

Versión actual: **v1.1.4**

LogMonitor es una plataforma de análisis de logs para operaciones. Recopila logs de Spring Boot locales o remotos, agrupa errores, muestra tendencias por espacio de nombres y puede solicitar análisis a proveedores LLM después de ocultar datos sensibles.

## Enlaces rápidos

- README completo en chino/inglés: [README raíz](../README.md)
- Arquitectura del servicio: [ARCHITECTURE.md](ARCHITECTURE.md)
- Arquitectura de ejecución: [RUNTIME_ARCHITECTURE.md](RUNTIME_ARCHITECTURE.md)
- Publicación e instalación: [deploy/README.md](../deploy/README.md)

## Funciones

- Vue 3 + TypeScript, Spring Boot 3 / JDK 17 y Agent remoto con JDK 8.
- MySQL 8.0.36+ en producción; H2 en memoria para la demo local.
- Recopilación local/remota, rotación, deduplicación y cola de disco de 5 GB.
- Métricas por espacio de nombres, URI, instancia y minuto, con desglose de errores.
- Enmascarado de token, Cookie, contraseñas, teléfonos, identificaciones e IP antes del LLM.

## Inicio local

Sigue [Quick Start](../README.md#quick-start) con JDK 17 y Node.js 20.19+/22.12+. Para registrar un Agent, consulta [instalación del Agent](../deploy/README.md#安装-agent).

## Versionado

Cada commit de desarrollo incrementa patch `+0.0.1`; cada lanzamiento formal incrementa minor `+0.1.0` y reinicia patch. Los tres componentes deben compartir versión.
