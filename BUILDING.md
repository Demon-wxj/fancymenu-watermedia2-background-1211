# 构建

先安装 **JDK 21 和 Gradle 8.12**。项目使用 Fabric Loom 1.9 系列；仓库没有 Gradle Wrapper，首次构建需要联网获取 Maven 依赖。

```powershell
gradle --version
gradle clean build --console=plain
```

构建产物在 `build/libs/`。安装生产 JAR，不要把 `-sources.jar` 或开发 JAR 放入游戏。

以下依赖需自行从合法渠道取得，按列出的文件名放入 `deps/`。仓库不附这些 JAR：

- `deps/fancymenu_fabric_3.9.1_MC_1.21.1.jar`（modCompileOnly）
- `deps/watermedia-2.1.37.jar`（modCompileOnly）
