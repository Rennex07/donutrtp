package net.zytonal.donutrtp.world;
import org.bukkit.World;
import org.bukkit.World.Environment;
public enum WorldType {
    NORMAL,
    NETHER,
    THE_END;
    public static WorldType fromWorld(World world) {
        if (world == null) {
            return NORMAL;
        }
        switch (world.getEnvironment()) {
            case NETHER:
                return NETHER;
            case THE_END:
                return THE_END;
            case NORMAL:
            default:
                return NORMAL;
        }
    }
    public int getDefaultMinY() {
        switch (this) {
            case NETHER:
                return 32;
            case THE_END:
                return 40;
            case NORMAL:
            default:
                return 5;
        }
    }
    public int getDefaultMaxY() {
        switch (this) {
            case NETHER:
                return 120;
            case THE_END:
            case NORMAL:
            default:
                return 255; 
        }
    }
}
