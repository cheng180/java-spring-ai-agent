# Maven 打包指南

> 以本项目（Spring Boot 4.1.0，artifactId=`AI`，version=`0.0.1-SNAPSHOT`，fat jar 打包）为实例。Maven 用 wrapper（`./mvnw`），无需全局安装 mvn。

---

## 一、先搞懂：生命周期 / 阶段 / goal

Maven 有三套独立的**生命周期（lifecycle）**：`clean`、`default`、`site`。每个生命周期由若干**阶段（phase）**组成，每个阶段又绑到具体插件的 **goal** 上执行。

```
clean 生命周期         default 生命周期（核心）
─────────────         ──────────────────────────────────
clean                  validate → compile → test → package → install → deploy
                       (前一个 phase 隐含执行后面所有 phase 之前的步骤)
```

**核心规则**：执行某个 phase，会**从该生命周期的第一个 phase 一路跑到指定 phase**。比如 `mvn package` 实际跑的是 `validate → ... → compile → test → package`，不是只跑 package 一步。

---

## 二、常用 phase 区别（重点）

| phase      | 做什么                                                      | 产物去哪                            | 本项目能否直接用 |
| ---------- | ---------------------------------------------------------- | ----------------------------------- | -------------- |
| `validate` | 校验项目与 POM 正确性                                      | 无                                  | ✅              |
| `compile`  | 编译 `src/main/java` → `target/classes`                   | `target/classes/*.class`            | ✅              |
| `test`     | 编译测试代码 + 跑 `src/test/java`（用 surefire）          | `target/surefire-reports/`          | ✅              |
| `package`  | 打成 jar/war（本项目经 spring-boot `repackage` 成 fat jar）| `target/AI-0.0.1-SNAPSHOT.jar`      | ✅              |
| `install`  | = package + 把产物装进**本地**仓库 `~/.m2/repository/`     | `~/.m2/repository/org/example/ai/AI/0.0.1-SNAPSHOT/` | ✅ |
| `deploy`   | = install + 把产物推到**远程**仓库（Nexus/私服/中央仓）    | 远程仓库                            | ❌ 见下方说明   |
| `clean`    | 单独生命周期，删除 `target/`                               | 无                                  | ✅              |

### package vs install vs deploy（最常被问）

- **`package`**：只在项目目录里产出 `target/xxx.jar`，**别的项目看不见它**。
- **`install`**：把同一个 jar 复制一份进**本地** `~/.m2`。之后**同一台机器上别的 Maven 项目** `依赖 org.example.ai:AI:0.0.1-SNAPSHOT` 就能从本地仓库直接解析，不用先 open 这个项目。
- **`deploy`**：再往上推一步，把 jar + pom + 源码/文档包发到**远程仓库**，**全团队 / CI 机器**都能拉到。

一句话：**package 出包，install 进本地仓库，deploy 进远程仓库**——范围一个比一个大。

### 为什么本项目跑不了 deploy

`deploy` 需要 pom 里配 `<distributionManagement>` 指明推到哪个远程仓库：

```xml
<distributionManagement>
  <repository>
    <id>nexus-releases</id>
    <url>http://nexus.xxx.com/repository/maven-releases/</url>
  </repository>
  <snapshotRepository>
    <id>nexus-snapshots</id>
    <url>http://nexus.xxx.com/repository/maven-snapshots/</url>
  </snapshotRepository>
</distributionManagement>
```

并在 `~/.m2/settings.xml` 里给同名 `<server>` 配凭证。本项目 pom **没配** distributionManagement，settings.xml 也**没有私服 server**，所以现在跑 `mvn deploy` 会报：

```
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-deploy-plugin
: Failed to deploy artifacts: ... Distribution management ... undefined
```

要支持 deploy，得先按上面的片段配好私服地址与凭证。

---

## 三、本项目打包产物的特殊性

### 1. 默认产物名

pom 没写 `<finalName>`，所以默认叫 `target/${artifactId}-${version}.jar` = `target/AI-0.0.1-SNAPSHOT.jar`。想改名为 `app.jar` 可在 `<build>` 里加：

```xml
<build>
  <finalName>app</finalName>
  ...
</build>
```

### 2. 手动 cp 成 app.jar

Dockerfile 里 `COPY app.jar app.jar` 写死了根目录的 `app.jar`，所以打完包要手动复制：

```bash
./mvnw clean package -DskipTests
cp target/AI-0.0.1-SNAPSHOT.jar app.jar
```

> 如果改了 `<finalName>app</finalName>`，产物直接就是 `target/app.jar`，可以少一步 cp，但 Dockerfile 的 COPY 路径也要相应改。

### 3. fat jar 结构（spring-boot-maven-plugin 的 repackage）

`package` 阶段先打普通 jar，再被 `repackage` goal 改造成可执行 fat jar：

```
app.jar
├── META-INF/MANIFEST.MF
│   ├── Main-Class:   org.springframework.boot.loader.launch.JarLauncher  ← java -jar 入口
│   └── Start-Class:  org.example.ai.AiApplication                        ← 真正的 main
├── org/springframework/boot/loader/       ← 启动器代码
├── BOOT-INF/classes/                      ← 你的业务 class + application.properties
├── BOOT-INF/lib/                          ← 全部第三方依赖（本项目约 141 个）
├── BOOT-INF/classpath.idx                 ← classpath 索引
└── BOOT-INF/layers.idx                    ← docker 分层索引
```

体积 ~142MB，代价是：一个包就是一个完整运行时，`java -jar app.jar` 直接能跑，无需另管 classpath。

### 4. 配置元数据（编译期顺带生成）

pom 的 `maven-compiler-plugin` 配了 `annotationProcessorPaths` → `spring-boot-configuration-processor`，编译期扫 `@ConfigurationProperties`，生成 `META-INF/spring-configuration-metadata.json`，IDE 里写 `application.properties` 才有自动补全。

---

## 四、在 IDEA 里怎么打包

有三种等价方式，任选：

### 方式 A：Maven 工具窗口（最直观，推荐新手）

1. 顶部菜单 `View → Tool Windows → Maven`，或快捷键 `Ctrl+Shift+A` 搜 "Maven"。
2. 右侧弹出 Maven 面板，展开项目 `AI` → **Lifecycle**。
3. 看到一排 phase 按钮：`clean / validate / compile / test / package / install / site / deploy`。
4. **双击 `package`** → IDEA 在底部 Run 窗口执行 `mvn package`，日志实时滚动。
5. 想先清后打：先双击 `clean` 再双击 `package`，或按住 `Ctrl` 同时选中 `clean` 和 `package` 再右键 `Run`。
6. 想跳测试：展开项目 → **Plugins → spring-boot-maven-plugin**，或用方式 C 在命令里加 `-DskipTests`。

### 方式 B：Run Configuration（可保存、可复用、可加参数）

1. 顶部 `Run → Edit Configurations...`。
2. 左上 `+` → 选 **Shell Script**（较新 IDEA）或 **Maven**：
   - 选 **Maven**：`Working directory` = 项目根，`Run` 栏填 `clean package -DskipTests`，命名如 `AI-package`，保存。
3. 之后直接点绿色三角形运行，参数固化不用每次敲。

### 方式 C：IDEA 内置终端（和命令行完全一致）

`Alt+F12` 打开 Terminal，直接敲：

```bash
./mvnw clean package -DskipTests
cp target/AI-0.0.1-SNAPSHOT.jar app.jar
```

> IDEA 会自动用项目自带的 `mvnw` wrapper，无需关心本机 mvn 是否在 PATH。

---

## 五、命令速查

| 目的                              | 命令                                            |
| -------------------------------- | --------------------------------------------- |
| 清掉 target                        | `./mvnw clean`                                |
| 只编译主代码                         | `./mvnw compile`                              |
| 打包（含测试）                      | `./mvnw package`                              |
| 打包跳测试                          | `./mvnw package -DskipTests`                  |
| 清+打包跳测试（最常用）             | `./mvnw clean package -DskipTests`            |
| 装进本地仓库（给别的项目用）        | `./mvnw install`                              |
| 推到远程私服（**本项目未配置，会报错**） | `./mvnw deploy`                               |
| 打包不编译测试代码（更快）          | `./mvnw package -Dmaven.test.skip=true`      |
| 只打普通 jar、不做 fat jar          | `./mvnw package -Dspring-boot.repackage.skip=true` |

### `-DskipTests` vs `-Dmaven.test.skip`

| 参数                       | 编译测试代码 | 执行测试 |
| ------------------------- | -------- | ----- |
| `-DskipTests`             | ✅ 会编译    | ❌ 跳过  |
| `-Dmaven.test.skip=true`  | ❌ 不编译    | ❌ 跳过  |

日常改了配置重新出包，用 `-DskipTests` 即可；想更快且不在乎测试能不能编译，用 `-Dmaven.test.skip=true`。

---

## 六、常见坑

1. **改了 `application.properties` 但 jar 里还是旧值**：没重新 `clean package`，target 里有旧缓存。加 `clean` 即可。校验：`unzip -p app.jar BOOT-INF/classes/application.properties | grep <key>`。
2. **Docker 构建拿到的还是旧 jar**：只打了 `target/` 里的 jar，没 `cp` 到根目录 `app.jar`，Dockerfile `COPY` 的是根目录那份。
3. **`mvn` 命令找不到**：本机没装全局 mvn，用项目自带 `./mvnw`（Windows 下也可 `./mvnw.cmd`）。
4. **deploy 报 "Distribution management undefined"**：pom 缺 `<distributionManagement>`，见第二节。
5. **Java 版本不对**：pom 要求 Java 17（`<java.version>17</java.version>`），本地实测 JDK 21 亦可编译（target 17）。`./mvnw -v` 可看当前用的 Java。IDEA 里 `File → Project Structure → SDK` 确认。
6. **打包慢**：首次下载依赖较久；之后靠 `~/.m2` 缓存。阿里云镜像已配在 settings.xml，正常应该不慢。

---

## 七、本项目标准打包流程

```bash
# 1. 清 + 打包（跳测试）
./mvnw clean package -DskipTests

# 2. 校验配置已打进包里
unzip -p target/AI-0.0.1-SNAPSHOT.jar BOOT-INF/classes/application.properties | grep chroma.client.host

# 3. 复制成 Dockerfile 要的 app.jar
cp target/AI-0.0.1-SNAPSHOT.jar app.jar

# 4.（可选）本地起一下验证
java -jar app.jar
```