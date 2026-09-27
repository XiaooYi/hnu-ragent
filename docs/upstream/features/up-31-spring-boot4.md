# UP-31 Spring Boot 4 升级

上游对照提交：`7f3a7f38 Spring Boot 4 升级与依赖架构调整`。

## 功能介绍

上游把 Spring Boot 升到 4.x，这是批次八（Agentic 体系）的前置：AgentScope、新的可观测性栈与 Boot 4 的
自动配置模型是配套的。本仓库此前停在 3.5.7，本次按要求完成升级。

升级不是「改一个版本号」，而是一次**依赖矩阵重排**：Boot 4 换了默认 JSON 实现、若干第三方 starter
也换了坐标。本次落地范围：

| 位置 | 变更 | 原因 |
| --- | --- | --- |
| `spring-boot.version` | `3.5.7` → `4.1.0` | 主版本升级（上游同版本） |
| MyBatis-Plus | `mybatis-plus-spring-boot3-starter:3.5.14` → `mybatis-plus-spring-boot4-starter:3.5.17` + 显式 `mybatis-plus-extension` | Boot 4 需要 boot4 starter；3.5.17 起 `extension` 不再传递依赖，`IService` / `ServiceImpl` 也迁到了新包 |
| Sa-Token | `sa-token-spring-boot3-starter:1.43.0` → `sa-token-spring-boot4-starter:1.45.0` | 官方为 Boot 4 提供的新 starter 坐标 |
| Redisson | `4.0.0` → `4.6.1` | Boot 4 适配版（自动配置类为 `RedissonAutoConfigurationV4`） |
| RocketMQ starter | `2.3.5` → `2.3.6` | 与 Boot 4 共存的可用版本 |
| **Jackson 2 支持** | 引入 Boot 4 官方模块 `spring-boot-jackson2` | Boot 4 默认 JSON 换成了 Jackson 3，容器里不再有 `com.fasterxml.jackson.databind.ObjectMapper`；本项目大量既有代码基于 Jackson 2。用官方模块恢复 Jackson 2 自动配置（继续尊重 `spring.jackson.*` 与模块注册），比手写 `ObjectMapper` bean 更稳 |
| **排除 RocketMQ 的 OTLP 导出链** | `framework/pom.xml` 排除 `io.opentelemetry:opentelemetry-exporter-otlp` | 该导出链会带 `okhttp-jvm:5.x`，与本项目使用的 OkHttp 4.12 同名类冲突（运行期 `NoSuchMethodError: Okio.socket`）；本项目不使用 OTel 导出 |
| **Okio 锁 3.9.0** | 新增 `okio.version` | 链路里仍存在 `okhttp-jvm 5.3.2`（其他传递依赖），OkHttp 4.12 默认的 okio 3.6 缺它需要的方法，统一到 3.9 后两者都能跑 |
| **Elasticsearch 客户端锁 8.18.8** | 新增 `elasticsearch-client.version` | Boot 4 默认管理 ES 9 客户端，其传输层已换名；本项目 `EsClientConfig` 基于 ES 8 的 low-level REST client。关键词检索默认关闭，先锁版本保持可用，迁 ES 9 客户端另行评估 |
| 意图树 Service 导入 | `com.baomidou.mybatisplus.extension.service[.impl]` → `com.baomidou.mybatisplus.spring.service[.impl]` | MyBatis-Plus 3.5.17 的包结构调整 |

**刻意不做的**：不迁移到 Jackson 3（影响面覆盖所有 JSON 处理与客户端兼容性，属独立议题）；
不迁到 ES 9 客户端（关键词检索默认关闭，先保可用）；不引入 AgentScope（架构决策 2 已定自研引擎）。

## 验收标准

1. **编译**：`./mvnw -pl bootstrap -am -DskipTests compile` 与 `-pl mcp-server` 均通过。
2. **测试**：`./mvnw -pl bootstrap -am clean test` → **291 项、0 失败、1 跳过、17 个环境依赖错误**，
   与升级前 Boot 3.5.7 基线的错误**数量与类别完全一致**（都是 Redis / PostgreSQL / Milvus / 真实模型
   未启动导致的 `@SpringBootTest` 上下文加载失败，根因是 `Unable to connect to Redis server: 192.168.227.128:6379`）。
   `./mvnw -pl mcp-server -am test` → **18 项、0 失败**（离线可跑）。
3. **上下文可启动**：容器启动阶段不再出现 `No qualifying bean of type 'com.fasterxml.jackson.databind.ObjectMapper'`
   之类的装配错误；Boot 4 下 Redisson 自动配置类为 `RedissonAutoConfigurationV4`（证明确实跑在 Boot 4）。
4. **无运行期版本冲突**：infra-ai 的 OkHttp 相关测试（`BaiLianEmbeddingBatchTest`、`BaiLianRerankClientTest`）
   不再抛 `NoSuchMethodError: okio.Okio.socket`。
5. **离线可构建**：新依赖已进本地仓库后，`./mvnw -o -pl bootstrap -am clean test` 能解析全部依赖。
6. 可执行验证：

```bash
./mvnw -pl bootstrap -am clean test
./mvnw -pl mcp-server -am test
```

## 代码位置

| 作用 | 位置 |
| --- | --- |
| 版本与依赖矩阵 | `pom.xml`（`spring-boot.version`、`mybatis-plus-*`、`sa-token`、`redisson`、`rocketmq`、`okio.version`、`elasticsearch-client.version`） |
| Jackson 2 自动配置 | `framework/pom.xml`（`spring-boot-jackson2`） |
| MyBatis-Plus / Sa-Token 坐标与排除 | `framework/pom.xml` |
| OTLP 排除（OkHttp 版本单一） | `framework/pom.xml`（RocketMQ starter 的 exclusion） |
| ES 客户端锁版本 | `pom.xml` dependencyManagement |
| 意图树包名调整 | `bootstrap/src/main/java/.../ingestion/service/IntentTreeService.java`、`.../service/impl/IntentTreeServiceImpl.java` |

## 相关图表

```mermaid
flowchart TD
    A["spring-boot 3.5.7"] --> B["spring-boot 4.1.0"]
    B --> C["MyBatis-Plus boot4 starter 3.5.17<br/>+ extension 显式依赖"]
    B --> D["Sa-Token boot4 starter 1.45.0"]
    B --> E["Redisson 4.6.1 / RocketMQ 2.3.6"]
    B --> F["默认 JSON 变 Jackson 3<br/>→ 引入 spring-boot-jackson2"]
    B --> G["传递依赖带 okhttp-jvm 5.x<br/>→ 排除 OTLP 导出链 + 锁 okio 3.9"]
    B --> H["默认管理 ES 9 客户端<br/>→ 锁 8.18.8 保持现实现可用"]
    C --> I["包名迁移<br/>extension.service → spring.service"]
```

## 与上游的差异

- **版本细节**：上游用 `mybatis-plus 3.5.17`、`sa-token 1.45.0`、`redisson 4.6.1`、`rocketmq 2.3.6`（本次完全对齐）；
  上游 MCP SDK 用 `0.17.0`（其 AgentScope 传递依赖压制后的版本），本仓库仍用已落地的 `1.1.2`，因此**多了一组
  OkHttp/Okio 冲突需要自己收口**（见上表）。
- **不上 Jackson 3**：上游的做法同样是保留 Jackson 2 —— 其 `pom.xml` 显式引入 `spring-boot-jackson2`
  （HEAD 版本中共 56 个源文件仍使用 `com.fasterxml.jackson`，无 `tools.jackson`）。本仓库与其对齐；
  真正迁到 Jackson 3 属独立议题（涉及注解包名、Feature 开关与 ES/MCP 客户端兼容性）。
- **不上 ES 9 客户端**：上游用 ES 9 客户端；本仓库关键词检索默认关闭，先锁 8.18.8 保证「打开就能用」。

## 上线事故与修复（2026-09-27）

本次升级上线后出现**全站 406（No acceptable representation）**，完整复盘见
[`docs/evaluation/case-spring-boot4-406-json-converter.md`](../../evaluation/case-spring-boot4-406-json-converter.md)。
要点：

1. **后果**：所有返回对象的接口（含 `/user/me`）都写不出 JSON，连全局异常处理器自身也失败。
2. **直接原因**：`bootstrap/.../rag/config/WebConfig.java` 覆写的是 `configureMessageConverters`
   并往里 `add` 了一个 UTF-8 的 `StringHttpMessageConverter`。Spring MVC 的
   `WebMvcConfigurationSupport#getMessageConverters()` 只在**列表仍为空**时才调用
   `addDefaultHttpMessageConverters`，因此 JSON 等默认转换器被整体跳过。
3. **放大原因**：本次只引入了 `spring-boot-jackson2`（拿到 Jackson 2 的 `ObjectMapper`），
   但没有声明 `spring.http.converters.preferred-json-mapper=jackson2`。
   Boot 4 的 Jackson 2 HTTP 转换器由 `@ConditionalOnProperty(havingValue="jackson2")`
   或「Jackson 3 不存在」二者之一触发，Jackson 3 在场时未声明首选会导致该转换器也不注册。
4. **修复**：`WebConfig` 改用 `extendMessageConverters` 做**就地替换**；
   `application.yaml` 显式声明 `spring.http.converters.preferred-json-mapper: jackson2`。
5. **回归**：新增 `bootstrap/src/test/.../rag/config/WebConfigTest.java`，把
   「不得向 `configureMessageConverters` 写入任何转换器」固化成用例。
