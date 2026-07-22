

package com.mewcode;

import com.mewcode.config.AppConfig;
import com.mewcode.config.ConfigLoader;
import com.mewcode.config.ProviderConfig;
import com.mewcode.llm.LlmClient;
import com.mewcode.print.PrintMode;
import com.mewcode.prompt.PromptBuilder;
import com.mewcode.remote.RemoteServer;
import com.mewcode.teams.TeamManager;
import com.mewcode.teams.TeamTools;
import com.mewcode.teams.TeammateRunner;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.impl.BashTool;
import com.mewcode.tool.impl.EditFileTool;
import com.mewcode.tool.impl.GlobTool;
import com.mewcode.tool.impl.GrepTool;
import com.mewcode.tool.impl.ReadFileTool;
import com.mewcode.tool.impl.WriteFileTool;
import com.mewcode.tui.MewCodeModel;

import com.mewcode.tui.tea.Program;

import java.util.List;
import sun.misc.Signal;

public class MewCode {

    // 默认 Remote 模式监听端口
    private static final String DEFAULT_REMOTE_ADDR = ":18888";

    public static void main(String[] args) {
        // 队友进程入口：被 tmux/iTerm 拉起时命令以 --teammate 打头，走队友模式而非 TUI。
        // 格式：mewcode --teammate --team-name <t> --agent-name <n>（见 SpawnDispatcher.buildTeammateCLI）
        if (args.length > 0 && args[0].equals("--teammate")) {
            runTeammate(args);
            return;
        }

        // 解析 CLI 参数：-p "prompt"、--output-format、--remote[=addr] 和配置文件路径
        String configPath = null;
        boolean remoteMode = false;
        String remoteAddr = DEFAULT_REMOTE_ADDR;
        String printPrompt = null;
        String outputFormat = "text";

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("-p") && i + 1 < args.length) {
                printPrompt = args[++i];
            } else if (arg.startsWith("-p=")) {
                printPrompt = arg.substring("-p=".length());
            } else if (arg.equals("--output-format") && i + 1 < args.length) {
                outputFormat = args[++i];
            } else if (arg.startsWith("--output-format=")) {
                outputFormat = arg.substring("--output-format=".length());
            } else if (arg.equals("--remote")) {
                remoteMode = true;
            } else if (arg.startsWith("--remote=")) {
                remoteMode = true;
                remoteAddr = arg.substring("--remote=".length());
            } else if (configPath == null) {
                configPath = arg;
            }
        }

        // 环境变量回退
        if (configPath == null) {
            String envPath = System.getenv("MEWCODE_CONFIG");
            if (envPath != null && !envPath.isBlank()) {
                configPath = envPath;
            }
        }

        AppConfig config;
        try {
            config = ConfigLoader.load(configPath);
        } catch (ConfigLoader.ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(1);
            return;
        }

        // -p 模式：非交互式运行，输出结果到 stdout
        if (printPrompt != null) {
            PrintMode.OutputFormat fmt = "stream-json".equals(outputFormat)
                    ? PrintMode.OutputFormat.STREAM_JSON
                    : PrintMode.OutputFormat.TEXT;
            PrintMode.run(config, printPrompt, fmt);
            return;
        }

        // --remote 模式：启动 HTTP + WebSocket 服务器，不进入 TUI
        if (remoteMode) {
            var server = new RemoteServer(
                    config.getProviders(),
                    config.getMcpServers() != null ? config.getMcpServers() : List.of(),
                    config.getHooks() != null ? config.getHooks() : List.of(),
                    remoteAddr,
                    config.isEnableCoordinatorMode()
            );
            try {
                server.run();
            } catch (Exception e) {
                System.err.println("Remote server error: " + e.getMessage());
                System.exit(1);
            }
            return;
        }

        // TUI 模式（默认）
        var model = new MewCodeModel(
                config.getProviders(),
                config.getMcpServers() != null ? config.getMcpServers() : List.of(),
                config.getHooks() != null ? config.getHooks() : List.of(),
                config.isEnableCoordinatorMode()
        );

        var program = new Program(model);

        model.setProgram(program);

        System.out.print("\033[?25l");

        // Re-register SIGINT handler after TUI4J's program.run() starts,
        // overriding the framework's default quit-on-SIGINT behavior.
        Thread.ofVirtual().start(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            try {
                Signal.handle(new Signal("INT"), sig -> model.handleSigint());
            } catch (IllegalArgumentException ignored) {}
        });

        try {
            program.run();
        } finally {
            System.out.print("\033[?25h");
            System.out.flush();
        }
    }

    /**
     * 队友工作进程入口：加载配置、建 LLM 客户端和工具白名单，接入 lead 建好的团队，
     * 然后进入 TeammateRunner 的常驻循环。对应 Go 版 cmd/mewcode/teammate.go 的 runTeammate。
     */
    private static void runTeammate(String[] args) {
        // 解析队友专用参数：只认 --team-name / --agent-name，其余忽略
        String teamName = null;
        String memberName = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--team-name") && i + 1 < args.length) {
                teamName = args[++i];
            } else if (args[i].equals("--agent-name") && i + 1 < args.length) {
                memberName = args[++i];
            }
        }
        if (teamName == null || teamName.isEmpty() || memberName == null || memberName.isEmpty()) {
            System.err.println("--teammate requires --team-name and --agent-name");
            System.exit(1);
            return;
        }

        // 加载与 TUI 相同的配置（默认路径 / MEWCODE_CONFIG 环境变量）
        AppConfig config;
        try {
            config = ConfigLoader.load(System.getenv("MEWCODE_CONFIG"));
        } catch (ConfigLoader.ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(1);
            return;
        }
        if (config.getProviders() == null || config.getProviders().isEmpty()) {
            System.err.println("no providers configured");
            System.exit(1);
            return;
        }
        ProviderConfig provider = config.getProviders().get(0);

        // 构建系统提示词并创建 LLM 客户端
        var env = PromptBuilder.detectEnvironment(provider.getModel());
        var options = new PromptBuilder.BuildOptions(null, null, null);
        String systemPrompt = PromptBuilder.buildSystemPrompt(env, options);
        LlmClient client = LlmClient.create(provider, systemPrompt);
        String protocol = provider.getProtocol();

        // 队友工具白名单：文件/代码工具 + SendMessage。队友不能建/删团队，那是 lead 的职责
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new EditFileTool());
        registry.register(new BashTool());
        registry.register(new GlobTool());
        registry.register(new GrepTool());

        // 本地 TeamManager 接入同一个团队：Team 的邮箱目录由团队名派生
        // （<user.dir>/.mewcode/teams/<team>/inboxes），与 lead 进程共享，从而收发消息。
        // 用 createTeamWith 登记而非 createTeam，避免清空 lead 已写入的共享任务库。
        TeamManager teamMgr = new TeamManager();
        TeamManager.Team team = new TeamManager.Team(teamName, TeamManager.TeamMode.IN_PROCESS);
        teamMgr.createTeamWith(team);
        registry.register(new TeamTools.SendMessageTool(teamMgr, memberName));

        TeamManager.Member member = team.addMember(memberName, client, registry, protocol, provider);

        // 关闭窗格 / Ctrl-C 时中断队友循环，让对话持久化等收尾逻辑得以执行
        Thread mainThread = Thread.currentThread();
        try {
            Signal.handle(new Signal("INT"), sig -> mainThread.interrupt());
        } catch (IllegalArgumentException ignored) {}
        try {
            Signal.handle(new Signal("TERM"), sig -> mainThread.interrupt());
        } catch (IllegalArgumentException ignored) {}

        String addendum = TeammateRunner.buildTeammateAddendum(teamName, memberName, null);

        // 不传初始 prompt：lead 在 spawn 前已把首个任务写入邮箱，循环首轮空闲轮询即可取到，
        // 传空可避免 runInProcessTeammate 注入重复的用户消息
        System.err.printf("[teammate %s/%s] booted, awaiting tasks%n", teamName, memberName);
        TeammateRunner.runInProcessTeammate(team, member, "", addendum);
    }
}

