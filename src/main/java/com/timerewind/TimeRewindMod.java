package com.timerewind;

import com.mojang.logging.LogUtils;
import com.timerewind.event.ForgeEvents;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * 時間巻き戻しMOD。
 * プレイヤーがダメージを受けると、そのダメージを無効化して数秒前の状態に戻す。
 * 周囲のブロック・モブ・ドロップアイテムも同じ時刻まで巻き戻す。
 */
@Mod(TimeRewindMod.MODID)
public class TimeRewindMod {
    public static final String MODID = "timerewind";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TimeRewindMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        MinecraftForge.EVENT_BUS.register(new ForgeEvents());
    }
}
