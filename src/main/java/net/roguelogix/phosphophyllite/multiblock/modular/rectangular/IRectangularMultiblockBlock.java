package net.roguelogix.phosphophyllite.multiblock.modular.rectangular;

import net.minecraft.MethodsReturnNonnullByDefault;
import net.roguelogix.phosphophyllite.multiblock.modular.IMultiblockBlock;

import javax.annotation.ParametersAreNonnullByDefault;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public interface IRectangularMultiblockBlock extends IMultiblockBlock {
    boolean isGoodForInterior();
    
    boolean isGoodForExterior();
    
    boolean isGoodForFrame();
    
    default boolean isGoodForCorner() {
        return isGoodForFrame();
    }
}
