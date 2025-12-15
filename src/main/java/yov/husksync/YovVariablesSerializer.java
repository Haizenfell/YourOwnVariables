package yov.husksync;

import net.william278.husksync.HuskSync;
import net.william278.husksync.data.Serializer;
import org.jetbrains.annotations.NotNull;

public class YovVariablesSerializer extends Serializer.Json<YovVariablesData> {

    public YovVariablesSerializer(@NotNull HuskSync plugin) {
        super(plugin, YovVariablesData.class);
    }
}
