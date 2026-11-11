package dev.smpmanager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigAndAccessTest {
    @TempDir Path temp;
    ManagerConfig load(String contents) throws Exception { Path file=temp.resolve(UUID.randomUUID()+".properties"); Files.writeString(file,contents); return new ManagerConfig(file); }
    @Test void defaultAndCustomConfigurationWorkWithoutDiscord() throws Exception {
        ManagerConfig defaults=load(""); assertEquals(5,defaults.startPlayers); assertEquals(4,defaults.minimumPlayers); assertEquals(300,defaults.graceSeconds); assertFalse(defaults.discordEnabled);
        ManagerConfig single=load("players-required-to-open=1\nminimum-online-players=1\ngrace-period-seconds=30\n"); assertEquals(1,single.startPlayers); assertEquals(30,single.graceSeconds);
    }
    @Test void invalidThresholdsTimingAndEnabledDiscordAreRejected() {
        assertThrows(IllegalArgumentException.class,()->load("players-required-to-open=2\nminimum-online-players=3\n"));
        assertThrows(IllegalArgumentException.class,()->load("grace-period-seconds=0\n"));
        assertThrows(IllegalArgumentException.class,()->load("discord.enabled=true\n"));
        assertThrows(IllegalArgumentException.class,()->load("afk.enabled=maybe\n"));
    }
    @Test void parseErrorsIdentifyTheKeyWithoutEchoingSecrets() {
        String secret="privateTokenThatMustNeverAppearInLogs";
        Exception error=assertThrows(IllegalArgumentException.class,()->load("grace-period-seconds="+secret));
        assertTrue(error.getMessage().contains("grace-period-seconds")); assertFalse(error.getMessage().contains(secret));
    }
    @Test void permissionSeparationCoversAllAdministrativeCommands() {
        CommandContext player=new CommandContext(UUID.randomUUID(),"Player","minecraft",false,false);
        CommandContext autopass=new CommandContext(UUID.randomUUID(),"Owner","minecraft",false,true);
        CommandContext admin=new CommandContext(null,"Admin","console",true,true);
        for(String verb:CommandAccess.PUBLIC) assertTrue(CommandAccess.allowed(player,verb));
        for(String verb:CommandAccess.ADMIN) { assertFalse(CommandAccess.allowed(player,verb)); assertFalse(CommandAccess.allowed(autopass,verb)); assertTrue(CommandAccess.allowed(admin,verb)); }
        assertTrue(CommandAccess.allowed(autopass,"autopass")); assertFalse(CommandAccess.allowed(player,"autopass"));
        assertFalse(CommandAccess.allowed(admin,"queue")); assertFalse(CommandAccess.allowed(admin,"quorummaintenance"));
    }
    @Test void playerSuggestionsDoNotExposeAdminActions() {
        CommandContext player=new CommandContext(UUID.randomUUID(),"Player","minecraft",false,false);
        assertEquals(Set.of("status","link","help"),new HashSet<>(CommandAccess.suggestions(player)));
    }
}
