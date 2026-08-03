# Maven 配置管理详解

> 以本项目实测配置为依据。Maven 用 wrapper（`./mvnw`，自带 Maven 3.9.16），`~/.m2/settings.xml` 只配了阿里云镜像。

---

## 一、配置的三层结构

Maven 的配置分散在三个地方，作用范围从大到小、优先级从小到大：

| 层 | 文件 | 作用范围 | 本机位置 |
|---|---|---|---|
| ① 全局 settings | `settings.xml` | 本机所有用户、所有项目 | `${maven.home}/conf/settings.xml`（wrapper 下：`C:\Users\admin\.m2\wrapper\dists\apache-maven-3.9.16\...\conf\settings.xml`）|
| ② 用户 settings | `settings.xml` | 当前用户的所有项目 | `~/.m2/settings.xml` |
| ③ 项目 pom | `pom.xml` | 仅本项目 | 项目根 `pom.xml` |

**优先级**：项目 pom > 用户 settings > 全局 settings。同一项配置，越靠后的层越能覆盖前面的。命令行参数（`-D`、`-s`、`-P`）又凌驾于这三层之上。

> 本机现状：全局 settings 是默认样例（全部注释掉，走默认值）；用户 settings 只配了阿里云镜像。所以生效的配置实际只有用户 settings 那一条 + pom。

**指定 settings 文件的 CLI 参数**：

```bash
./mvnw -s /path/to/user/settings.xml ...      # 用指定用户 settings
./mvnw -gs /path/to/global/settings.xml ...   # 用指定全局 settings
```

---

## 二、settings.xml 详解

settings.xml 管**机器/用户级**的运行环境：本地仓库在哪、去哪个镜像下、私服凭证、代理、profile。它**不含项目信息**。

### 2.1 完整骨架

```xml
<settings>
  <localRepository>/path/to/local/repo</localRepository>  <!-- 默认 ~/.m2/repository -->
  <interactiveMode>false</interactiveMode>                <!-- 默认 true，是否交互提示 -->
  <offline>false</offline>                                 <!-- 默认 false，离线模式 -->
  <servers>...</servers>        <!-- 私服凭证 -->
  <mirrors>...</mirrors>        <!-- 镜像（拦截/替换仓库请求）-->
  <proxies>...</proxies>        <!-- HTTP/HTTPS 代理 -->
  <profiles>...</profiles>      <!-- 环境相关配置组 -->
  <activeProfiles>...</activeProfiles>  <!-- 默认激活的 profile -->
</settings>
```

### 2.2 各段含义 + 本机对照

#### `<localRepository>` — 本地仓库路径

Maven 下载的所有 jar 都缓存到这。**本项目未设**，走默认 `~/.m2/repository`。改了路径要记得把旧仓库里的内容一起迁过去，否则重新下载。

#### `<mirrors>` — 镜像

**本机实际配的就是这一段**：

```xml
<mirrors>
  <mirror>
    <id>aliyun</id>
    <name>Aliyun Maven</name>
    <mirrorOf>central</mirrorOf>              <!-- 关键：拦截哪些仓库 -->
    <url>https://maven.aliyun.com/repository/public</url>
  </mirror>
</mirrors>
```

`<mirrorOf>` 决定拦截范围，常用取值：

| mirrorOf | 含义 |
|---|---|
| `central` | 只拦截中央仓库（默认） |
| `*` | 拦截所有仓库 |
| `external:*` | 拦截所有非 localhost/非 file 仓库 |
| `repo1,repo2` | 指定 id 的仓库 |
| `*,!repo1` | 所有仓库除了 repo1 |

> ⚠️ **mirror 是替换不是新增**：配了 `mirrorOf=central` 后，对中央仓库的请求**全部**走阿里云，原 central 地址不再被访问。区别于 `<repositories>`（那是新增一个仓库源）。

#### `<servers>` — 私服凭证

mirror 只是改 URL，**访问需要鉴权的私服**才用 `<servers>`。`<id>` 必须和 pom 里 `<repository>`/`<distributionManagement>` 的 `<id>` **一一对应**：

```xml
<servers>
  <server>
    <id>nexus-releases</id>          <!-- 必须与仓库 id 匹配 -->
    <username>user</username>
    <password>pwd</password>
    <!-- 或用私钥 -->
    <!-- <privateKey>${user.home}/.ssh/id_rsa</privateKey> -->
  </server>
</servers>
```

> **本机现状：没有 `<servers>`**，所以 `mvn deploy` 推到鉴权私服时会 401。

#### `<proxies>` — 网络代理

公司网络走代理时用。**本机未配**。

```xml
<proxies>
  <proxy>
    <id>my-proxy</id>
    <active>true</active>
    <protocol>http</protocol>
    <host>proxy.company.com</host>
    <port>8080</port>
    <nonProxyHosts>*.company.com|localhost</nonProxyHosts>
  </proxy>
</proxies>
```

#### `<profiles>` + `<activeProfiles>` — 环境配置组

settings 里的 profile 可以定义仓库、插件仓库、属性等，按条件激活。**本机未配**。示例（默认激活一个 dev profile）：

```xml
<profiles>
  <profile>
    <id>dev-env</id>
    <activation>
      <activeByDefault>true</activeByDefault>   <!-- 默认激活 -->
    </activation>
    <properties>
      <db.url>jdbc:h2:mem:dev</db.url>
    </properties>
    <repositories>
      <repository>
        <id>internal-repo</id>
        <url>http://nexus.company.com/group</url>
        <releases><enabled>true</enabled></releases>
        <snapshots><enabled>true</enabled></snapshots>
      </repository>
    </repositories>
  </profile>
</profiles>
<activeProfiles>
  <activeProfile>dev-env</activeProfile>   <!-- 强制激活 -->
</activeProfiles>
```

---

## 三、pom.xml 配置详解

pom 管**项目级**配置：GAV 坐标、依赖、插件、构建、发布。下面按段落讲，并标出本项目实际怎么配的。

### 3.1 GAV 坐标 + parent

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>4.1.0</version>
  <relativePath/>            <!-- 从仓库找 parent -->
</parent>
<groupId>org.example</groupId>
<artifactId>AI</artifactId>
<version>0.0.1-SNAPSHOT</version>
```

- **parent 继承**：本项目继承 spring-boot-starter-parent，自动拿到：默认依赖版本管理（spring-boot 的 BOM）、默认插件配置（spring-boot-maven-plugin 等）、默认编码/Java 版本等。**这是为什么本项目 pom 不用写 spring-boot 依赖版本号**——parent 里都管好了。
- `<relativePath/>`：parent 不在父目录里，从仓库解析。

### 3.2 `<packaging>` — 打包类型

不写则默认 `jar`。可选 `jar / war / pom / maven-plugin / ear` 等。**本项目未写 = jar**。

### 3.3 `<properties>` — 属性变量

```xml
<properties>
  <java.version>17</java.version>
  <spring-ai.version>2.0.0</spring-ai.version>
</properties>
```

- `java.version=17`：被 spring-boot-starter-parent 用来设 `maven.compiler.release=17`，即**字节码目标是 Java 17**。⚠️ 这跟"构建用哪个 JDK 跑"是两回事——本项目实际用 JDK 21 跑 mvn（`./mvnw -v` 显示 Java 21.0.7），但产物的字节码兼容到 17。所以 jar 能在 JDK 17+ 运行。
- `spring-ai.version`：被下方 dependencyManagement 引用（`${spring-ai.version}`）。

**属性引用语法**：`${变量名}`。属性来源优先级见第五节。

### 3.4 `<dependencies>` vs `<dependencyManagement>`

二者最容易混。本项目同时用了，正好对照：

```xml
<!-- dependencyManagement：只声明版本，不真正引入 -->
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-bom</artifactId>
      <version>${spring-ai.version}</version>
      <type>pom</type>
      <scope>import</scope>      <!-- 导入整个 BOM 的依赖管理 -->
    </dependency>
  </dependencies>
</dependencyManagement>

<!-- dependencies：真正引入依赖（版本由上面 BOM 管） -->
<dependencies>
  <dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-model-anthropic</artifactId>
    <!-- 不写 version，由 spring-ai-bom 统一管 -->
  </dependency>
  ...
</dependencies>
```

| | `<dependencies>` | `<dependencyManagement>` |
|---|---|---|
| 是否引入 | ✅ 真正加入 classpath | ❌ 只声明版本，不引入 |
| 子模块继承 | 强制继承 | 声明继承但需子模块显式声明才引入 |
| 作用 | 我要用这个依赖 | 统一管这个依赖的版本 |
| 典型用法 | 项目直接依赖 | BOM 统一版本、多模块版本对齐 |

`<scope>import</scope>` + `<type>pom</type>` 是**导入 BOM**的特殊用法：把 spring-ai-bom 里管理的所有依赖版本搬进本 pom 的 dependencyManagement，于是所有 spring-ai 子模块都不用写 version。

### 3.5 `<scope>` — 依赖范围

本项目用到了 `compile`(默认)/`runtime`/`test`。完整表：

| scope | 编译 | 测试 | 运行 | 打进 fat jar | 典型 |
|---|---|---|---|---|---|
| `compile`（默认） | ✅ | ✅ | ✅ | ✅ | 绝大多数依赖 |
| `provided` | ✅ | ✅ | ❌ | ❌ | 容器/运行环境已有，如 servlet-api |
| `runtime` | ❌ | ✅ | ✅ | ✅ | 运行时才需要，如 JDBC 驱动 |
| `test` | ❌ | ✅ | ❌ | ❌ | 仅测试用，如 junit |
| `system` | ✅ | ✅ | ✅ | ✅ | 手动指定本地 jar，少用 |
| `import` | — | — | — | — | 仅 dependencyManagement 用，导入 BOM |

> 本项目 `spring-boot-devtools`、`docker-compose` 用了 `runtime`+`optional=true`：运行时需要但不强制传递给依赖本项目的模块。`sqlite-jdbc` 写死了 version 3.45.1.0（没走 BOM）。

### 3.6 `<repositories>` vs `<distributionManagement>`

- `<repositories>`：**从哪下载**依赖/插件。本项目没配，靠 settings 的 aliyun 镜像 + 默认 central。
- `<pluginRepositories>`：从哪下载**插件**。同上。
- `<distributionManagement>`：**推到哪发布**。本项目**没配**，所以 `deploy` 不可用（详见打包指南）。

```xml
<distributionManagement>
  <repository>
    <id>nexus-releases</id>      <!-- 对应 settings.xml 里 <server> 的 id -->
    <url>http://nexus.company.com/maven-releases/</url>
  </repository>
  <snapshotRepository>
    <id>nexus-snapshots</id>
    <url>http://nexus.company.com/maven-snapshots/</url>
  </snapshotRepository>
</distributionManagement>
```

> 规则：`-SNAPSHOT` 版本推到 snapshotRepository，RELEASE 推到 repository。`0.0.1-SNAPSHOT` 属于快照版。

### 3.7 `<build>` — 构建配置

本项目 build 段含 `spring-boot-maven-plugin` 和 `maven-compiler-plugin`（配了 annotationProcessorPaths 生成配置元数据）。还可放：

- `<finalName>`：产物名（不写默认 `${artifactId}-${version}`，本项目即 `AI-0.0.1-SNAPSHOT`）
- `<resources>`/`<testResources>`：哪些非 java 文件打进 jar（默认 `src/main/resources`）
- `<filters>`：占位符替换（`${...}` 注入 properties 值到资源文件）
- `<plugins>`：构建插件
- `<pluginManagement>`：插件版本声明（类似 dependencyManagement）

### 3.8 `<profiles>`（pom 内）

settings 也有 profile，但 pom 里的 profile 能配更多（build、plugins、dependencies 等），用于不同环境构建。本项目没用，示例：

```xml
<profiles>
  <profile>
    <id>prod</id>
    <build>
      <resources>
        <resource>
          <directory>src/main/resources/prod</directory>
        </resource>
      </resources>
    </build>
    <properties>
      <chroma.host>http://prod-chroma:8001</chroma.host>
    </properties>
  </profile>
</profiles>
```

激活：`./mvnw -Pprod package`。

---

## 四、配置优先级与属性解析

### 4.1 配置覆盖顺序（高 → 低）

```
命令行 -D / -P   >   pom.xml   >   用户 settings.xml   >   全局 settings.xml
```

举例：pom properties 里 `java.version=17`，命令行 `-Djava.version=21` 能覆盖（但不建议这么干，会改字节码目标）。

### 4.2 属性 `${var}` 的解析来源（高 → 低）

1. 命令行 `-Dxxx=yyy`
2. pom 里 `<properties>` 声明的
3. settings profile 里的 properties
4. 系统/环境变量（`${env.HOME}`、`${java.home}`）
5. parent pom 继承下来的 properties

> 优先级高的覆盖低的。同名属性，命令行能覆盖 pom。

---

## 五、Profile 激活机制

profile 让"同一份代码，不同环境"。激活方式有 5 种：

| 激活方式 | 配置示例 | 说明 |
|---|---|---|
| 默认激活 | `<activeByDefault>true</activeByDefault>` | 没有 -P 时自动激活 |
| 命令行 | `./mvnw -Pprod` | 显式激活；`-P !dev` 表示禁用 |
| JDK 版本 | `<jdk>[17,21)</jdk>` | JDK 17 到 21 之间激活 |
| 操作系统 | `<os><family>windows</family></os>` | 按系统激活 |
| 文件存在 | `<file><exists>...</exists></file>` | 文件存在/缺失才激活 |
| settings 里 activeProfiles | `<activeProfile>dev</activeProfile>` | 强制激活 |

排查 profile 实际激活了哪些：

```bash
./mvnw help:active-profiles          # 看激活了哪些 profile
./mvnw help:effective-pom            # 看合并后的最终 pom（含继承/激活）
./mvnw help:effective-settings       # 看合并后的最终 settings
```

> `help:effective-pom` 是排查"Maven 到底用了什么配置"的最强工具——把 parent、profile、properties 全部展开成一份最终 pom。

---

## 六、mirror vs repository（最常混淆）

| | `<repositories>` (pom) | `<mirrors>` (settings) |
|---|---|---|
| 作用 | 新增一个下载源 | 替换/拦截已有仓库的请求 |
| 是否新增源 | ✅ 多了一个仓库 | ❌ 还是那些仓库，只是换 URL |
| 配在哪 | pom | settings |
| 典型 | 公司私服（central 之外的依赖在这） | 中央仓库太慢，镜像走阿里云 |

举例：pom 配 `<repository><id>internal</id>...`，settings 想把这个也走阿里云，要 `mirrorOf=*`（或 `mirrorOf=*,!internal` 排除）。本机 `mirrorOf=central` 只拦中央仓库，私服不受影响。

---

## 七、传递性依赖与冲突解决

依赖有传递性：A 依赖 B，B 依赖 C，A 间接拿到 C。规则：

- **最近原则**：A→B→C(1.0)，同时 A→D→C(2.0)，看 A 到 C 哪条路径短。路径长度相同时**先声明者胜**（pom 里写在前面的赢）。
- **scope 影响**：`provided`/`test` 不参与传递；`compile` 和 `runtime` 传递但 scope 可能被降级（如 B 是 compile、A 依赖 B 是 test，C 到 A 这里就降成 test）。
- **optional=true**：依赖在本项目用，但不传递给依赖本项目的模块。本项目 devtools、docker-compose 都 optional=true。

排查依赖树：

```bash
./mvnw dependency:tree                           # 完整依赖树
./mvnw dependency:tree -Dincludes=org.example:*  # 只看某 groupId
./mvnw dependency:analyze                        # 分析用到的/没用到的依赖
```

---

## 八、本项目配置全貌（一张表）

| 配置项 | 本项目现状 | 说明 |
|---|---|---|
| 全局 settings | 默认样例（全注释） | 走默认值 |
| 用户 settings | 仅 aliyun mirror | mirrorOf=central，本地仓库走默认 `~/.m2/repository` |
| parent | spring-boot-starter-parent 4.1.0 | 继承 BOM + 插件默认配置 |
| packaging | 未写（=jar） | fat jar 由 repackage 生成 |
| properties | java.version=17, spring-ai.version=2.0.0 | 字节码目标 17，实际用 JDK 21 构建 |
| dependencies | 9 个直接依赖 | devtools/docker-compose 用 runtime+optional |
| dependencyManagement | import spring-ai-bom 2.0.0 | 统一管 spring-ai 子模块版本 |
| repositories | 未配 | 走 aliyun 镜像 + central |
| distributionManagement | **未配** | deploy 不可用 |
| build plugins | spring-boot-maven-plugin + compiler（带配置处理器） | 生成 fat jar + 配置元数据 |
| profiles | 未配 | 单一环境 |
| .mvn/jvm.config | `-Dfile.encoding=UTF-8 -Duser.language=zh` | mvnw 启动的 JVM 参数 |
| .mvn/wrapper | wrapper 3.3.4, Maven 3.9.16 | 自动下载 Maven |

---

## 九、常见问题排查

1. **依赖下载慢/失败**：先看 `~/.m2/settings.xml` 有没有镜像；本机已配阿里云，正常不慢。公司网还要看是否需要 `<proxies>`。
2. **版本冲突，不知道实际用了哪个版本**：`./mvnw dependency:tree -Dincludes=<groupId>:<artifactId>` 看是哪条路径带进来的。
3. **配置不知道最终生效成什么**：`./mvnw help:effective-pom` / `help:effective-settings`。
4. **不知道哪个 profile 激活了**：`./mvnw help:active-profiles`。
5. **deploy 报 401/无权限**：`~/.m2/settings.xml` 缺 `<servers>` 凭证，且 `<id>` 要和 distributionManagement 里的一致。
6. **改了 settings 不生效**：可能 IDEA 缓存了旧 settings 路径；`File → Invalidate Caches` 或重启 IDEA。Maven 工具窗口 → ⚙️ → 检查 `User settings file` 路径是否指向 `~/.m2/settings.xml`。

---

## 十、IDEA 里管理 Maven 配置的入口

- **Settings → Build → Build Tools → Maven**：
  - `Maven home path`：用 wrapper 时可指向 `.mvn/wrapper` 或 IDEA 内置。
  - `User settings file`：确认指向 `~/.m2/settings.xml`（勾上 Override 才生效）。
  - `Local repository`：默认 `~/.m2/repository`，一般不用改。
- **右侧 Maven 工具窗口 → ⚙️**：切换是否用 IDEA 内置 Maven、是否用 settings override。
- **Profiles 视图**：Maven 面板里 pom 有 profile 时会出现勾选框，可视化激活/禁用。