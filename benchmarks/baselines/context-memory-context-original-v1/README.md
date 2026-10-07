# 冻结的原版 Context 模块

来自首次开发轮次的封存源码，按每个文件 SHA-256 复核后复制；没有模型输出、题目或真值。正式压缩对照只替换这个模块，Provider、Engine、文件工具、验证与评分统一使用修复后的运行时，避免将额外验证调用差异归因于压缩。

原版与候选之间，Context 主源码仅 AnchorSnapshotPlanner.java、DefaultContextPipeline.java 不同；公共契约源码相同。编译产物另存显式输出目录并记录 JDK、编译命令与哈希。源码归档本身不代表正式对照已实现或已运行。
