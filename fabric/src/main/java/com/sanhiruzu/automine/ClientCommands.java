package com.sanhiruzu.automine;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class ClientCommands {
    private ClientCommands() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(literal("automine")
                .then(literal("status")
                        .executes(context -> sendStatus(context.getSource())))
                .then(literal("open_world")
                        .then(argument("world", StringArgumentType.string())
                                .executes(context -> {
                                    ClientAutomation.openWorld(StringArgumentType.getString(context, "world"));
                                    return 1;
                                })))
                .then(literal("ami_quest_status")
                        .executes(context -> {
                            ClientAutomation.writeAmiQuestStatus("");
                            return 1;
                        })
                        .then(argument("item", StringArgumentType.string())
                                .executes(context -> {
                                    ClientAutomation.writeAmiQuestStatus(StringArgumentType.getString(context, "item"));
                                    return 1;
                                })))
                .then(literal("ami_search")
                        .then(argument("query", StringArgumentType.greedyString())
                                .executes(context -> {
                                    ClientAutomation.writeAmiSearch(StringArgumentType.getString(context, "query"), 100, "LIST", "ALL", false, "");
                                    return 1;
                                })))
                .then(literal("ami_reindex")
                        .executes(context -> {
                            ClientAutomation.requestAmiReindex(true);
                            return 1;
                        }))
                .then(literal("ami_reindex_and_search")
                        .then(argument("query", StringArgumentType.greedyString())
                                .executes(context -> {
                                    ClientAutomation.requestAmiReindexAndSearch(StringArgumentType.getString(context, "query"), 100, "LIST", "ALL", false, true, "");
                                    return 1;
                                })))
                .then(literal("run_command")
                        .then(argument("command", StringArgumentType.greedyString())
                                .executes(context -> {
                                    ClientAutomation.runCommand(StringArgumentType.getString(context, "command"));
                                    return 1;
                                })))
                .then(literal("run_chat")
                        .then(argument("message", StringArgumentType.greedyString())
                                .executes(context -> {
                                    ClientAutomation.runChat(StringArgumentType.getString(context, "message"));
                                    return 1;
                                })))
                .then(literal("open_atelier_codex")
                        .executes(context -> {
                            ClientAutomation.openAtelierCodex();
                            return 1;
                        }))
                .then(literal("open_screen")
                        .then(argument("screen", StringArgumentType.greedyString())
                                .executes(context -> {
                                    ClientAutomation.openScreen(StringArgumentType.getString(context, "screen"));
                                    return 1;
                                })))
                .then(literal("screen_info")
                        .executes(context -> {
                            ClientAutomation.writeScreenInfo();
                            return 1;
                        }))
                .then(literal("screen_targets")
                        .executes(context -> {
                            ClientAutomation.writeScreenTargets();
                            return 1;
                        }))
                .then(literal("click_screen")
                        .then(argument("x", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg())
                                .then(argument("y", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg())
                                        .then(argument("button", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                                                .executes(context -> {
                                                    ClientAutomation.clickScreen(
                                                            com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(context, "x"),
                                                            com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(context, "y"),
                                                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "button"));
                                                    return 1;
                                                })))))
                .then(literal("click_screen_target")
                        .then(argument("target", StringArgumentType.string())
                                .then(argument("button", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                                        .executes(context -> {
                                            ClientAutomation.clickScreenTarget(
                                                    StringArgumentType.getString(context, "target"),
                                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "button"));
                                            return 1;
                                        }))))
                .then(literal("press_key")
                        .then(argument("keyCode", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .executes(context -> {
                                    ClientAutomation.pressKey(
                                            com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "keyCode"),
                                            0,
                                            0);
                                    return 1;
                                })))
                .then(literal("open_inventory")
                        .executes(context -> {
                            ClientAutomation.openInventory();
                            return 1;
                        }))
                .then(literal("close_screen")
                        .executes(context -> {
                            ClientAutomation.closeScreen();
                            return 1;
                        }))
                .then(literal("screenshot")
                        .executes(context -> {
                            ClientAutomation.scheduleScreenshot("automine.png", 3);
                            return 1;
                        })
                        .then(argument("name", StringArgumentType.word())
                                .executes(context -> {
                                    ClientAutomation.scheduleScreenshot(StringArgumentType.getString(context, "name"), 3);
                                    return 1;
                                })))
                .then(literal("open_inventory_and_screenshot")
                        .executes(context -> {
                            if (ClientAutomation.openInventory()) {
                                ClientAutomation.scheduleScreenshot("inventory.png", 3);
                            }
                            return 1;
                        })
                        .then(argument("name", StringArgumentType.word())
                                .executes(context -> {
                                    if (ClientAutomation.openInventory()) {
                                        ClientAutomation.scheduleScreenshot(StringArgumentType.getString(context, "name"), 3);
                                    }
                                    return 1;
                                }))));
    }

    private static int sendStatus(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("AutoMine command file: "
                + ClientAutomation.commandFile().toAbsolutePath()).withStyle(ChatFormatting.GREEN));
        source.sendFeedback(Component.literal("AutoMine status file: "
                + ClientAutomation.statusFile().toAbsolutePath()).withStyle(ChatFormatting.GRAY));
        return 1;
    }
}
