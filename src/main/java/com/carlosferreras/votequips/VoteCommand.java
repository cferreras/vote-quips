package com.carlosferreras.votequips;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

/**
 * /votar y /vote: envía a quien lo ejecuta un mensaje privado con el enlace de votación. Sin permisos.
 * /votar reload y /vote reload: recarga frases.yml. Requiere OP (nivel 2) o el permiso vote-quips:reload.
 */
public final class VoteCommand {

    private static final Permission RELOAD = new Permission.Atom(Identifier.fromNamespaceAndPath("vote-quips", "reload"));
    private static final Predicate<CommandSourceStack> CAN_RELOAD = src ->
            src.permissions().hasPermission(RELOAD)
                    || src.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS));

    private VoteCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, Supplier<QuipsConfig> config,
                                Consumer<QuipsConfig> setConfig, Path configFile) {
        for (String name : new String[]{"votar", "vote"}) {
            dispatcher.register(Commands.literal(name)
                    .executes(ctx -> run(ctx, config.get()))
                    .then(Commands.literal("reload").requires(CAN_RELOAD)
                            .executes(ctx -> reload(ctx, setConfig, configFile))));
        }
    }

    private static int run(CommandContext<CommandSourceStack> ctx, QuipsConfig config) {
        ctx.getSource().sendSystemMessage(buildMessage(config));
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx, Consumer<QuipsConfig> setConfig, Path configFile) {
        try {
            QuipsConfig config = QuipsConfig.loadStrict(configFile);
            setConfig.accept(config);
            ctx.getSource().sendSuccess(() -> Component.literal("vote-quips recargado: " + config.phrases().size()
                    + " categorías de frases.").withStyle(ChatFormatting.GREEN), true);
            return 1;
        } catch (Exception e) {
            VoteQuips.LOGGER.error("Error recargando {}", configFile, e);
            ctx.getSource().sendFailure(Component.literal("Error en frases.yml, se mantiene la configuración anterior: "
                    + e.getMessage()));
            return 0;
        }
    }

    static Component buildMessage(QuipsConfig config) {
        String message = config.vote("mensaje");
        int index = message.indexOf("{enlace}");
        String before = index >= 0 ? message.substring(0, index) : message;
        String after = index >= 0 ? message.substring(index + "{enlace}".length()) : "";
        if (index < 0 && !before.isEmpty() && !before.endsWith(" ")) {
            before += " ";
        }

        return Component.literal(before).append(link(config)).append(Component.literal(after));
    }

    private static Component link(QuipsConfig config) {
        String url = config.vote("url").trim();
        MutableComponent link = Component.literal(config.vote("texto_enlace"));
        URI uri = parseUrl(url);
        if (uri == null) {
            VoteQuips.LOGGER.warn("La URL de votación '{}' no es válida (debe empezar por http:// o https://)", url);
            return link.append(Component.literal(" " + url));
        }
        String hover = config.vote("texto_hover");
        return link.withStyle(style -> {
            style = style.withClickEvent(new ClickEvent.OpenUrl(uri));
            return hover.isEmpty() ? style : style.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover)));
        });
    }

    private static URI parseUrl(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null) return null;
            scheme = scheme.toLowerCase(Locale.ROOT);
            return scheme.equals("http") || scheme.equals("https") ? uri : null;
        } catch (Exception e) {
            return null;
        }
    }
}
