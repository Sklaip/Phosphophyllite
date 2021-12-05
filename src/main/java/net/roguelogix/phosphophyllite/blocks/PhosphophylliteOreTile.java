package net.roguelogix.phosphophyllite.blocks;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.roguelogix.phosphophyllite.modular.tile.PhosphophylliteTile;
import net.roguelogix.phosphophyllite.quartz.*;
import net.roguelogix.phosphophyllite.registry.RegisterTileEntity;
import net.roguelogix.phosphophyllite.repack.org.joml.Matrix4f;
import net.roguelogix.phosphophyllite.repack.org.joml.Vector3f;
import net.roguelogix.phosphophyllite.repack.org.joml.Vector3i;

import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
@RegisterTileEntity(name = "phosphophyllite_ore")
public class PhosphophylliteOreTile extends PhosphophylliteTile {
    
    @RegisterTileEntity.Type
    static BlockEntityType<?> TYPE;
    
    public PhosphophylliteOreTile(BlockPos pWorldPosition, BlockState pBlockState) {
        super(TYPE, pWorldPosition, pBlockState);
    }
    
    static {
        Quartz.EVENT_BUS.addListener(PhosphophylliteOreTile::onQuartzStartup);
    }
    
    private static QuartzStaticMesh mesh;
    
    static void onQuartzStartup(QuartzEvent.Startup quartzStartup) {
        Quartz.registerRenderType(RenderType.solid());
        Quartz.registerRenderType(RenderType.entityCutout(InventoryMenu.BLOCK_ATLAS));
        mesh = Quartz.createStaticMesh((builder) -> {
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(Blocks.STONE.defaultBlockState(), builder.matrixStack(), builder.bufferSource(), 0, 0x00000, net.minecraftforge.client.model.data.EmptyModelData.INSTANCE);
        });
    }
    
    int instanceID = -1;
    QuartzDynamicMatrix quartzMatrix;
    QuartzDynamicLight quartzLight;
    Matrix4f spinMatrix = new Matrix4f();
    float rotation = 0;
    
    @Override
    public void onAdded() {
        assert level != null;
        if (!level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 0);
            return;
        }
        if (mesh != null && level.isClientSide()) {
            var modelPos = new Vector3i(getBlockPos().getX(), getBlockPos().getY() + 1, getBlockPos().getZ());
            quartzLight = Quartz.createDynamicLight(modelPos, QuartzDynamicLight.Type.SMOOTH);
            quartzMatrix = Quartz.createDynamicMatrix((matrix, nanoSinceLastFrame, partialTicks, playerBlock, playerPartialBlock) -> {
                rotation += nanoSinceLastFrame / 1_000_000_000f;
                spinMatrix.identity();
//                spinMatrix.scale(1.5f);
//                spinMatrix.translate(-0.1875f, -0.1875f, -0.1875f);
                spinMatrix.scale(0.5f);
                spinMatrix.translate(0.5f, 0.5f, 0.5f);
                spinMatrix.translate(0.5f, 0.5f, 0.5f);
                spinMatrix.rotate(rotation, new Vector3f(1, 1, 1).normalize());
                spinMatrix.translate(-0.5f, -0.5f, -0.5f);
//                spinMatrix.translate(0, 2, 0);
                matrix.write(spinMatrix);
            });
            instanceID = Quartz.registerStaticMeshInstance(mesh, modelPos, quartzMatrix, new Matrix4f().translate(0, 0, 0), quartzLight);
        }
    }
    
    private static byte AOMode(boolean sideA, boolean corner, boolean sideB) {
        if (sideA && sideB) {
            return 3;
        }
        if ((sideA || sideB) && corner) {
            return 2;
        }
        if (sideA || sideB || corner) {
            return 1;
        }
        return 0;
    }
    
    @Override
    public void onRemoved(boolean chunkUnload) {
        assert level != null;
        if (mesh != null && level.isClientSide()) {
            Quartz.unregisterStaticMeshInstance(instanceID);
            instanceID = -1;
            quartzMatrix.dispose();
            quartzLight.dispose();
        }
    }
}
