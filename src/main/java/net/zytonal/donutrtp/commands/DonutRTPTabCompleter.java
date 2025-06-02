package net.zytonal.donutrtp.commands;
import net.zytonal.donutrtp.DonutRTP;
import org.bukkit.command.Command;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
public class DonutRTPTabCompleter implements TabCompleter {
    private final DonutRTP plugin;
    public DonutRTPTabCompleter(DonutRTP plugin) {
        this.plugin = plugin;
    }
    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            completions.add("reload");
            for (World world : Bukkit.getWorlds()) {
                completions.add(world.getName());
            }
            
            return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .sorted()
                    .collect(Collectors.toList());
        }
        return completions;
    }
}
