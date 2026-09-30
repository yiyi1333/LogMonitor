# LogMonitor

Versão atual: **v1.1.3**

LogMonitor é uma plataforma operacional para análise de logs. Ela coleta logs Spring Boot locais ou remotos, agrupa erros, agrega tendências por namespace da aplicação e pode chamar provedores LLM após mascarar dados sensíveis.

## Links rápidos

- README completo em chinês/inglês: [README raiz](../README.md)
- Arquitetura do serviço: [ARCHITECTURE.md](ARCHITECTURE.md)
- Arquitetura de execução: [RUNTIME_ARCHITECTURE.md](RUNTIME_ARCHITECTURE.md)
- Publicação e instalação: [deploy/README.md](../deploy/README.md)

## Recursos

- Vue 3 + TypeScript, centro Spring Boot 3 / JDK 17 e Agent remoto JDK 8.
- MySQL 8.0.36+ em produção; H2 em memória para demonstração local.
- Coleta local/remota, rotação, deduplicação e fila em disco de 5 GB.
- Agregação por namespace, URI, instância e minuto, com detalhamento de erros.
- Mascaramento de token, Cookie, senhas, telefones, documentos e IP antes do LLM.

## Execução local

Siga o [Quick Start](../README.md#quick-start) com JDK 17 e Node.js 20.19+/22.12+. Para registrar um Agent, consulte [instalação do Agent](../deploy/README.md#安装-agent).

## Versionamento

Cada commit de desenvolvimento incrementa patch em `+0.0.1`; cada release formal incrementa minor em `+0.1.0` e zera patch. Os três componentes devem usar a mesma versão.
