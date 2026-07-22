
package com.mewcode.teams;

import com.mewcode.agent.Agent;
import com.mewcode.agent.AgentEvent;
import com.mewcode.config.ProviderConfig;
import com.mewcode.conversation.ConversationManager;
import com.mewcode.llm.LlmClient;
import com.mewcode.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * Manages multi-agent teams with mailbox-based communication.
 */
public class TeamManager {

    public enum TeamMode { IN_PROCESS, TMUX, ITERM }

    private final Map<String, Team> teams = new LinkedHashMap<>();
    // 每个团队一份共享任务库，落盘在 <团队目录>/tasks.json
    private final Map<String, SharedTaskStore> taskStores = new LinkedHashMap<>();

    public synchronized Team createTeam(String name, TeamMode mode) {
        Team team = new Team(name, mode);
        teams.put(name, team);
        // 新建团队时初始化一份空的共享任务库
        SharedTaskStore store = new SharedTaskStore(teamDir(name).resolve("tasks.json"));
        store.initEmpty();
        taskStores.put(name, store);
        return team;
    }

    /**
     * 注册一个已经构造好的 Team，不重置共享任务库。供被 tmux/iTerm 拉起的队友进程
     * 接入 lead 建好的团队：只登记团队，tasks.json 按需从磁盘懒加载，避免像 createTeam
     * 那样清空 lead 已写入的任务。
     */
    public synchronized Team createTeamWith(Team team) {
        teams.put(team.getName(), team);
        return team;
    }

    public synchronized Team getTeam(String name) {
        return teams.get(name);
    }

    /**
     * 获取团队的共享任务库；内存无缓存时（例如队友进程）从磁盘 tasks.json 加载。
     */
    public synchronized SharedTaskStore getTaskStore(String teamName) {
        SharedTaskStore cached = taskStores.get(teamName);
        if (cached != null) {
            return cached;
        }
        SharedTaskStore store = new SharedTaskStore(teamDir(teamName).resolve("tasks.json"));
        taskStores.put(teamName, store);
        return store;
    }

    public synchronized void deleteTeam(String name) {
        Team team = teams.remove(name);
        if (team != null) {
            // 解绑该团队成员在全局名称注册表里的映射
            AgentNameRegistry registry = AgentNameRegistry.getInstance();
            for (String member : team.memberNames()) {
                registry.unregister(member);
            }
            team.stopAll();
        }
        taskStores.remove(name);
    }

    public synchronized List<String> listTeams() {
        return new ArrayList<>(teams.keySet());
    }

    public synchronized void closeAll() {
        for (Team team : teams.values()) {
            team.stopAll();
        }
        teams.clear();
    }

    public synchronized List<TeammateProgress> getAllTeammateProgress() {
        return teams.values().stream()
                .flatMap(t -> t.getTeammateProgressList().stream())
                .toList();
    }

    /**
     * 后端自动检测，对齐 Claude Code：只有当前进程已身处 tmux / iTerm2 会话时才用窗格后端，
     * 否则回退进程内。tmux 和 iTerm2 会自动给会话内进程设上 TMUX / ITERM_SESSION_ID 环境变量，
     * 用户无需手动配置。
     */
    public static TeamMode detectBackend() {
        // Windows 护栏：tmux 窗格 spawn 时用 pwsh 执行 POSIX 命令会 ParserError，一律走进程内。
        String os = System.getProperty("os.name");
        if (os != null && os.toLowerCase().contains("windows")) {
            return TeamMode.IN_PROCESS;
        }
        return detectBackendFromEnv();
    }

    /**
     * 只按环境变量判断后端，不含平台判断，抽出来便于在任意平台单测。
     */
    static TeamMode detectBackendFromEnv() {
        return detectBackendFromEnv(System.getenv("TMUX"), System.getenv("ITERM_SESSION_ID"));
    }

    /**
     * 直接传入环境变量值的可测版本：TMUX 非空 → TMUX，ITERM_SESSION_ID 非空 → ITERM，都无 → 进程内。
     */
    static TeamMode detectBackendFromEnv(String tmux, String itermSessionId) {
        if (tmux != null && !tmux.isEmpty()) {
            return TeamMode.TMUX;
        }
        if (itermSessionId != null && !itermSessionId.isEmpty()) {
            return TeamMode.ITERM;
        }
        return TeamMode.IN_PROCESS;
    }

    // ── Inner classes ──────────────────────────────────────────────────

    private static Path teamsBaseDir() {
        return Path.of(System.getProperty("user.dir"), ".mewcode", "teams");
    }

    private static Path teamDir(String name) {
        return teamsBaseDir().resolve(name);
    }

    public static class Team {
        final String name;
        final TeamMode mode;
        final Map<String, Member> members = new LinkedHashMap<>();
        private final FileMailBox mailBox;

        public Team(String name, TeamMode mode) {
            this.name = name;
            this.mode = mode;
            this.mailBox = new FileMailBox(teamsBaseDir().resolve(name).resolve("inboxes"));
        }

        public String getName() { return name; }
        public TeamMode getMode() { return mode; }

        public FileMailBox getMailBox() { return mailBox; }

        public synchronized Member addMember(String name, LlmClient client, ToolRegistry registry,
                                             String protocol, ProviderConfig cfg) {
            Agent ag = new Agent(client, registry, protocol, cfg);
            Member member = new Member(name, ag, new ConversationManager());
            members.put(name, member);
            return member;
        }

        public synchronized BlockingQueue<AgentEvent> startMember(String name, String task) {
            Member member = members.get(name);
            if (member == null) return null;
            member.conv.addUserMessage(task);
            BlockingQueue<AgentEvent> queue = member.agent.run(member.conv);
            member.active = true;
            return queue;
        }

        public synchronized void stopMember(String name) {
            Member member = members.get(name);
            if (member != null) {
                member.active = false;
                if (member.thread != null) {
                    member.thread.interrupt();
                }
            }
        }

        public synchronized void stopAll() {
            for (Member m : members.values()) {
                m.active = false;
                if (m.thread != null) m.thread.interrupt();
            }
        }

        public synchronized Member getMember(String name) {
            return members.get(name);
        }

        public synchronized boolean hasMember(String name) {
            return members.containsKey(name);
        }

        public synchronized List<String> memberNames() {
            return new ArrayList<>(members.keySet());
        }

        public void sendMessage(String from, String to, String content) {
            mailBox.send(to, new FileMailBox.MailMessage(from, content));
        }

        public List<TeammateProgress> getTeammateProgressList() {
            return members.values().stream()
                    .filter(m -> m.progress != null)
                    .map(m -> m.progress)
                    .toList();
        }
    }

    public static class Member {
        public final String name;
        public final Agent agent;
        public final ConversationManager conv;
        public volatile boolean active;
        public volatile Thread thread;
        public TeammateProgress progress;

        public Member(String name, Agent agent, ConversationManager conv) {
            this.name = name;
            this.agent = agent;
            this.conv = conv;
        }

        public String getName() { return name; }
        public boolean isActive() { return active; }
    }

}
