package net.roguelogix.phosphophyllite.registry;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Material;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.event.CreativeModeTabEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fluids.FluidType;
import net.minecraftforge.fluids.ForgeFlowingFluid;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.forgespi.language.ModFileScanData;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import net.roguelogix.phosphophyllite.config.ConfigManager;
import net.roguelogix.phosphophyllite.threading.WorkQueue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.*;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class Registry {
    
    private final Logger LOGGER;
    
    private final WorkQueue blockRegistrationQueue = new WorkQueue();
    
    private RegisterEvent.RegisterHelper<Block> blockRegistryEvent;
    
    private final WorkQueue itemRegistrationQueue = new WorkQueue();
    private RegisterEvent.RegisterHelper<Item> itemRegistryEvent;
    
    private final ResourceLocation creativeTabResourceLocation;
    private final Component creativeTabTitle;
    private Item itemGroupItem = Items.STONE;
    private final ObjectArrayList<Item> creativeTabItems = new ObjectArrayList<>();
    
    private final WorkQueue fluidRegistrationQueue = new WorkQueue();
    private RegisterEvent.RegisterHelper<Fluid> fluidRegistryEvent;
    
    private final WorkQueue fluidTypeRegistrationQueue = new WorkQueue();
    private RegisterEvent.RegisterHelper<FluidType> fluidTypeRegistryEvent;
    private final WorkQueue containerRegistrationQueue = new WorkQueue();
    private RegisterEvent.RegisterHelper<MenuType<?>> containerRegistryEvent;
    
    private final WorkQueue tileRegistrationQueue = new WorkQueue();
    private RegisterEvent.RegisterHelper<BlockEntityType<?>> tileRegistryEvent;
    private final Map<Class<?>, LinkedList<Block>> tileBlocks = new HashMap<>();
    
    private final WorkQueue registerCapabilityQueue = new WorkQueue();
    private RegisterCapabilitiesEvent registerCapabilitiesEvent;
    
    private final WorkQueue clientSetupQueue = new WorkQueue();
    private FMLClientSetupEvent clientSetupEvent;
    
    private final WorkQueue commonSetupQueue = new WorkQueue();
    private FMLCommonSetupEvent commonSetupEvent;

//    private final ArrayList<Runnable> biomeLoadingEventHandlers = new ArrayList<>();
//    private BiomeLoadingEvent biomeLoadingEvent;
    
    private final Map<String, AnnotationHandler> annotationMap = new Object2ObjectOpenHashMap<>();
    
    {
        annotationMap.put(RegisterBlock.class.getName(), this::registerBlockAnnotation);
        annotationMap.put(RegisterItem.class.getName(), this::registerItemAnnotation);
        annotationMap.put(RegisterFluid.class.getName(), this::registerFluidAnnotation);
        annotationMap.put(RegisterContainer.class.getName(), this::registerContainerAnnotation);
        annotationMap.put(RegisterTile.class.getName(), this::registerTileAnnotation);
        annotationMap.put(RegisterCapability.class.getName(), this::registerCapabilityAnnotation);
//        annotationMap.put(RegisterOre.class.getName(), this::registerWorldGenAnnotation);
    }
    
    public Registry() {
        String callerClass = new Exception().getStackTrace()[1].getClassName();
        String callerPackage = callerClass.substring(0, callerClass.lastIndexOf("."));
        String modNamespace = callerPackage.substring(callerPackage.lastIndexOf(".") + 1);
        ModFileScanData modFileScanData = FMLLoader.getLoadingModList().getModFileById(modNamespace).getFile().getScanResult();
        
        LOGGER = LogManager.getLogger("Phosphophyllite/Registry/" + modNamespace);
        
        creativeTabResourceLocation = new ResourceLocation(modNamespace, "creative_tab");
        creativeTabTitle = Component.translatable("item_group." + modNamespace);
        
        final Map<String, AnnotationHandler> onModLoadMap = new Object2ObjectOpenHashMap<>();
        onModLoadMap.put(OnModLoad.class.getName(), this::onModLoadAnnotation);
        final Map<String, AnnotationHandler> registerConfigMap = new Object2ObjectOpenHashMap<>();
        registerConfigMap.put(RegisterConfig.class.getName(), this::registerConfigAnnotation);
        
        // these two are special cases that need to be handled first
        // in case anything needs config options during static construction
        handleAnnotationTypes(modFileScanData, callerPackage, modNamespace, registerConfigMap, true);
        // this is used for module registration, which need to happen before block registration
        handleAnnotationTypes(modFileScanData, callerPackage, modNamespace, onModLoadMap, true);
        
        handleAnnotationTypes(modFileScanData, callerPackage, modNamespace, annotationMap, false);
        
        
        IEventBus ModBus = FMLJavaModLoadingContext.get().getModEventBus();
        
        ModBus.addListener(this::registerEvent);

//        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH, this::biomeLoadingEventHandler);
        ModBus.addListener(this::commonSetupEventHandler);
        ModBus.addListener(this::creativeTabEvent);
        
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ModBus.addListener(this::clientSetupEventHandler);
        }
    }
    
    private void handleAnnotationTypes(ModFileScanData modFileScanData, String callerPackage, String modNamespace, Map<String, AnnotationHandler> annotations, boolean requiredCheck) {
        for (ModFileScanData.AnnotationData annotation : modFileScanData.getAnnotations()) {
            final var annotationClassName = annotation.annotationType().getClassName();
            AnnotationHandler handler = annotations.get(annotationClassName);
            if (handler == null) {
                // not an annotation i handle
                continue;
            }
            String className = annotation.clazz().getClassName();
            if (className.startsWith(callerPackage)) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Attempting to handle " + annotationClassName + " in class " + className + " on member " + annotation.memberName());
                }
                try {
                    Class<?> clazz = Registry.class.getClassLoader().loadClass(className);
                    if (clazz.isAnnotationPresent(ClientOnly.class) && !FMLEnvironment.dist.isClient()) {
                        continue;
                    }
                    if (clazz.isAnnotationPresent(SideOnly.class)) {
                        var sideOnly = clazz.getAnnotation(SideOnly.class);
                        if (sideOnly.value() != FMLEnvironment.dist) {
                            continue;
                        }
                    }
                    // class loaded, so, pass it off to the handler
                    handler.run(modNamespace, clazz, annotation.memberName());
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Failed to handle required annotation " + annotation.annotationType().getClassName() + " in class " + className + " on member " + annotation.memberName() + " with error " + e);
                    }
                    if (requiredCheck) {
                        var isRequired = annotation.annotationData().get("required");
                        if (isRequired instanceof Boolean && (Boolean) isRequired) {
                            e.printStackTrace();
                            throw new IllegalStateException("Failed to handle required annotation " + annotation.annotationType().getClassName() + " in class " + className);
                        }
                    }
                }
            }
        }
    }
    
    private void creativeTabEvent(CreativeModeTabEvent.Register event) {
        if (creativeTabItems.isEmpty()) {
            return;
        }
        event.registerCreativeModeTab(creativeTabResourceLocation, builder -> {
            builder.title(creativeTabTitle);
            builder.icon(() -> new ItemStack(itemGroupItem));
            builder.displayItems((enabledFlags, populator, hasPermissions) -> {
                final var creativeTabItemStacks = new ObjectArrayList<ItemStack>(creativeTabItems.size());
                creativeTabItems.forEach(item -> creativeTabItemStacks.add(new ItemStack(item)));
                creativeTabItemStacks.sort((o1, o2) -> o1.getDisplayName().getString().compareToIgnoreCase(o2.getDisplayName().getString()));
                creativeTabItemStacks.forEach(populator::accept);
            });
        });
    }
    
    private void registerEvent(RegisterEvent registerEvent) {
        registerEvent.register(ForgeRegistries.Keys.BLOCKS, this::blockRegistration);
        registerEvent.register(ForgeRegistries.Keys.ITEMS, this::itemRegistration);
        registerEvent.register(ForgeRegistries.Keys.FLUIDS, this::fluidRegistration);
        registerEvent.register(ForgeRegistries.Keys.FLUID_TYPES, this::fluidTypeRegistration);
        registerEvent.register(ForgeRegistries.Keys.MENU_TYPES, this::containerRegistration);
        registerEvent.register(ForgeRegistries.Keys.BLOCK_ENTITY_TYPES, this::tileEntityRegistration);
    }
    
    private void blockRegistration(RegisterEvent.RegisterHelper<Block> event) {
        blockRegistryEvent = event;
        blockRegistrationQueue.runAll();
        blockRegistryEvent = null;
    }
    
    private void itemRegistration(RegisterEvent.RegisterHelper<Item> event) {
        itemRegistryEvent = event;
        itemRegistrationQueue.runAll();
        itemRegistryEvent = null;
    }
    
    private void fluidRegistration(RegisterEvent.RegisterHelper<Fluid> event) {
        fluidRegistryEvent = event;
        fluidRegistrationQueue.runAll();
        fluidRegistryEvent = null;
    }
    
    private void fluidTypeRegistration(RegisterEvent.RegisterHelper<FluidType> event) {
        fluidTypeRegistryEvent = event;
        fluidTypeRegistrationQueue.runAll();
        fluidTypeRegistryEvent = null;
    }
    
    private void containerRegistration(RegisterEvent.RegisterHelper<MenuType<?>> containerTypeRegistryEvent) {
        containerRegistryEvent = containerTypeRegistryEvent;
        containerRegistrationQueue.runAll();
        containerRegistryEvent = null;
    }
    
    private void tileEntityRegistration(RegisterEvent.RegisterHelper<BlockEntityType<?>> tileEntityTypeRegister) {
        tileRegistryEvent = tileEntityTypeRegister;
        tileRegistrationQueue.runAll();
        tileRegistryEvent = null;
    }
    
    private void clientSetupEventHandler(FMLClientSetupEvent event) {
        clientSetupEvent = event;
        clientSetupQueue.runAll();
        clientSetupEvent = null;
    }
    
    private void commonSetupEventHandler(FMLCommonSetupEvent event) {
        commonSetupEvent = event;
        commonSetupQueue.runAll();
        commonSetupEvent = null;
    }

//    private void biomeLoadingEventHandler(BiomeLoadingEvent event) {
//        biomeLoadingEvent = event;
//        biomeLoadingEventHandlers.forEach(Runnable::run);
//        biomeLoadingEvent = null;
//    }
    
    private interface AnnotationHandler {
        void run(final String modNamespace, final Class<?> clazz, final String memberName);
    }
    
    private void registerBlockAnnotation(final String modNamespace, final Class<?> blockClazz, final String memberName) {
        if (blockClazz.isAnnotationPresent(IgnoreRegistration.class)) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Registration of block at " + memberName + " in " + blockClazz.getName() + " ignored");
            }
            return;
        }
        
        blockRegistrationQueue.enqueue(() -> {
            final Block block;
            final RegisterBlock annotation;
            try {
                final Field field = blockClazz.getDeclaredField(memberName);
                if (field.isAnnotationPresent(IgnoreRegistration.class)) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Registration of block at " + memberName + " in " + blockClazz.getName() + " ignored");
                    }
                    return;
                }
                if (!Modifier.isStatic(field.getModifiers())) {
                    LOGGER.warn("Non-static block instance variable " + memberName + " in " + blockClazz.getSimpleName());
                    return;
                }
                field.setAccessible(true);
                block = (Block) field.get(null);
                annotation = field.getAnnotation(RegisterBlock.class);
                
                if (!Modifier.isFinal(field.getModifiers())) {
                    LOGGER.warn("Non-final block instance variable " + memberName + " in " + blockClazz.getSimpleName());
                }
            } catch (NoSuchFieldException e) {
                LOGGER.error("Unable to find block field for block " + memberName + " in " + blockClazz.getSimpleName());
                return;
            } catch (IllegalAccessException e) {
                LOGGER.error("Unable to access block field for block " + memberName + " in " + blockClazz.getSimpleName());
                return;
            }
            
            if (block == null) {
                LOGGER.warn("Null block instance variable " + memberName + " in " + blockClazz.getSimpleName());
                return;
            }
            
            String modid = annotation.modid();
            if (modid.equals("")) {
                modid = modNamespace;
            }
            String name = annotation.name();
            if (modid.equals("")) {
                LOGGER.error("Unable to register block without a name from class " + blockClazz.getSimpleName());
                return;
            }
            
            if (!Block.class.isAssignableFrom(blockClazz)) {
                LOGGER.error("Attempt to register block from class not extended from Block. " + blockClazz.getSimpleName());
                return;
            }
            
            
            final String registryName = modid + ":" + name;
            
            if (annotation.tileEntityClass() != BlockEntity.class) {
                tileBlocks.computeIfAbsent(annotation.tileEntityClass(), k -> new LinkedList<>()).add(block);
            }
            
            blockRegistryEvent.register(new ResourceLocation(registryName), block);
            
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Block registered: " + registryName);
            }
            
            if (annotation.registerItem()) {
                boolean creativeTabBlock = blockClazz.isAnnotationPresent(CreativeTabBlock.class);
                itemRegistrationQueue.enqueue(() -> {
                    var item = new BlockItem(block, new Item.Properties());
                    if (annotation.creativeTab()) {
                        creativeTabItems.add(item);
                    }
                    itemRegistryEvent.register(new ResourceLocation(registryName), item);
                    if (creativeTabBlock) {
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("Creative tab item set as " + registryName + " for mod " + modNamespace);
                        }
                        itemGroupItem = item;
                    }
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("BlockItem registered for " + registryName);
                    }
                });
            }
        });
    }
    
    private void registerItemAnnotation(String modNamespace, Class<?> itemClazz, final String memberName) {
        if (itemClazz.isAnnotationPresent(IgnoreRegistration.class)) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Registration of item at " + memberName + " in " + itemClazz.getName() + " ignored");
            }
            return;
        }
        
        itemRegistrationQueue.enqueue(() -> {
            
            final Item item;
            final RegisterItem annotation;
            try {
                final Field field = itemClazz.getDeclaredField(memberName);
                if (field.isAnnotationPresent(IgnoreRegistration.class)) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Registration of item at " + memberName + " in " + itemClazz.getName() + " ignored");
                    }
                    return;
                }
                field.setAccessible(true);
                item = (Item) field.get(null);
                annotation = field.getAnnotation(RegisterItem.class);
                
                if (!Modifier.isFinal(field.getModifiers())) {
                    LOGGER.warn("Non-final item instance variable " + memberName + " in " + itemClazz.getSimpleName());
                }
            } catch (NoSuchFieldException e) {
                LOGGER.error("Unable to find item field for block " + memberName + " in " + itemClazz.getSimpleName());
                return;
            } catch (IllegalAccessException e) {
                LOGGER.error("Unable to access item field for block " + memberName + " in " + itemClazz.getSimpleName());
                return;
            }
            
            if (item == null) {
                LOGGER.warn("Null item instance variable " + memberName + " in " + itemClazz.getSimpleName());
                return;
            }
            
            String modid = annotation.modid();
            if (modid.equals("")) {
                modid = modNamespace;
            }
            String name = annotation.name();
            if (modid.equals("")) {
                LOGGER.error("Unable to register item " + memberName + " in " + itemClazz.getSimpleName() + " without a registry name");
                return;
            }
            
            final String registryName = modid + ":" + name;
            
            if (annotation.creativeTab()) {
                creativeTabItems.add(item);
            }
            
            itemRegistryEvent.register(new ResourceLocation(registryName), item);
            
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Item registered: " + registryName);
            }
        });
    }
    
    private void registerFluidAnnotation(String modNamespace, Class<?> fluidClazz, final String memberName) {
        if (fluidClazz.isAnnotationPresent(IgnoreRegistration.class)) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Registration of fluid at " + memberName + " in " + fluidClazz.getName() + " ignored");
            }
            return;
        }
        
        blockRegistrationQueue.enqueue(() -> {
            
            assert fluidClazz.isAnnotationPresent(RegisterFluid.class);
            
            final RegisterFluid annotation = fluidClazz.getAnnotation(RegisterFluid.class);
            
            String modid = annotation.modid().equals("") ? modNamespace : annotation.modid();
            String name = annotation.name();
            if (modid.equals("")) {
                LOGGER.error("Unable to register fluid without a name");
                return;
            }
            
            
            if (!ForgeFlowingFluid.class.isAssignableFrom(fluidClazz)) {
                LOGGER.error("Attempt to register fluid from class not extended from PhosphophylliteFluid");
                return;
            }
            
            final String baseRegistryName = modid + ":" + name;
            final var baseResourceLocation = new ResourceLocation(baseRegistryName);
            
            PhosphophylliteFluid[] fluids = new PhosphophylliteFluid[2];
            Item[] bucketArray = new Item[1];
            LiquidBlock[] blockArray = new LiquidBlock[1];
            
            Constructor<?> constructor;
            try {
                constructor = fluidClazz.getDeclaredConstructor(ForgeFlowingFluid.Properties.class);
            } catch (NoSuchMethodException e) {
                LOGGER.error("Failed to find constructor to create instance of " + fluidClazz.getSimpleName());
                return;
            }
            
            Supplier<? extends PhosphophylliteFluid> stillSupplier = () -> fluids[0];
            Supplier<? extends PhosphophylliteFluid> flowingSupplier = () -> fluids[1];
            final var fluidType = new FluidType(FluidType.Properties.create()) {
                @Override
                public void initializeClient(Consumer<IClientFluidTypeExtensions> consumer) {
                    consumer.accept(new IClientFluidTypeExtensions() {
                        final ResourceLocation stillTexture = new ResourceLocation(modid, "fluid/" + name + "_still");
                        final ResourceLocation flowingTexture = new ResourceLocation(modid, "fluid/" + name + "_flowing");
                        final ResourceLocation overlayTexture = new ResourceLocation(modid, "fluid/" + name + "_overlay");
                        
                        @Override
                        public ResourceLocation getStillTexture() {
                            return stillTexture;
                        }
                        
                        @Override
                        public ResourceLocation getFlowingTexture() {
                            return flowingTexture;
                        }
                        
                        @Override
                        public ResourceLocation getOverlayTexture() {
                            return overlayTexture;
                        }
                        
                        @Override
                        public int getTintColor() {
                            return annotation.color();
                        }
                    });
                }
            };
            
            ForgeFlowingFluid.Properties properties = new ForgeFlowingFluid.Properties(() -> fluidType, stillSupplier, flowingSupplier);
            if (annotation.registerBucket()) {
                properties.bucket(() -> bucketArray[0]);
            }
            properties.block(() -> blockArray[0]);
            
            PhosphophylliteFluid stillInstance;
            PhosphophylliteFluid flowingInstance;
            
            try {
                stillInstance = (PhosphophylliteFluid) constructor.newInstance(properties);
                flowingInstance = (PhosphophylliteFluid) constructor.newInstance(properties);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                LOGGER.error("Exception caught when instantiating instance of " + fluidClazz.getSimpleName());
                e.printStackTrace();
                return;
            }
            
            stillInstance.isSource = true;
            
            fluids[0] = stillInstance;
            fluids[1] = flowingInstance;
            blockArray[0] = new LiquidBlock(stillSupplier, Block.Properties.of(Material.WATER).noCollission().explosionResistance(100.0F).noLootTable());
            
            for (Field declaredField : fluidClazz.getDeclaredFields()) {
                if (declaredField.isAnnotationPresent(RegisterFluid.Instance.class)) {
                    if (!declaredField.getType().isAssignableFrom(fluidClazz)) {
                        LOGGER.error("Unassignable instance variable " + declaredField.getName() + " in " + fluidClazz.getSimpleName());
                        continue;
                    }
                    if (!Modifier.isStatic(declaredField.getModifiers())) {
                        LOGGER.error("Cannot set non-static instance variable " + declaredField.getName() + " in " + fluidClazz.getSimpleName());
                        continue;
                    }
                    declaredField.setAccessible(true);
                    try {
                        declaredField.set(null, stillInstance);
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }
            }
            
            blockRegistryEvent.register(baseResourceLocation, blockArray[0]);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("FluidBlock registered: " + baseResourceLocation);
            }
            
            fluidRegistrationQueue.enqueue(() -> {
                PhosphophylliteFluid still = fluids[0];
                PhosphophylliteFluid flowing = fluids[1];
                if (still == null || flowing == null) {
                    return;
                }
                
                fluidRegistryEvent.register(baseResourceLocation, still);
                fluidRegistryEvent.register(new ResourceLocation(baseRegistryName + "_flowing"), flowing);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Fluid registered: " + baseResourceLocation);
                }
            });
            
            if (annotation.registerBucket()) {
                itemRegistrationQueue.enqueue(() -> {
                    BucketItem bucket = new BucketItem(() -> fluids[0], new Item.Properties().craftRemainder(Items.BUCKET).stacksTo(1));
                    creativeTabItems.add(bucket);
                    bucketArray[0] = bucket;
                    itemRegistryEvent.register(new ResourceLocation(baseRegistryName + "_bucket"), bucket);
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Bucket registered: " + baseResourceLocation);
                    }
                });
            }
            
            fluidTypeRegistrationQueue.enqueueUntracked(() -> {
                fluidTypeRegistryEvent.register(baseResourceLocation, fluidType);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("FluidType registered: " + baseResourceLocation);
                }
            });
        });
    }
    
    // TODO: move this to solution similar to what is used for tile entities
    private void registerContainerAnnotation(String modNamespace, Class<?> containerClazz, final String memberName) {
        if (containerClazz.isAnnotationPresent(IgnoreRegistration.class)) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Registration of container at " + memberName + " in " + containerClazz.getName() + " ignored");
            }
            return;
        }
        
        assert containerClazz.isAnnotationPresent(RegisterContainer.class);
        
        final RegisterContainer annotation = containerClazz.getAnnotation(RegisterContainer.class);
        
        String modid = annotation.modid();
        if (modid.equals("")) {
            modid = modNamespace;
        }
        String name = annotation.name();
        if (modid.equals("")) {
            LOGGER.error("Unable to register container without a name");
            return;
        }
        
        
        if (!AbstractContainerMenu.class.isAssignableFrom(containerClazz)) {
            LOGGER.error("Attempt to register container from class not extended from Container");
            return;
        }
        
        final String registryName = modid + ":" + name;
        
        MenuType<?>[] containerTypeArray = new MenuType[1];
        
        containerRegistrationQueue.enqueue(() -> {
            ContainerSupplier supplier = null;
            for (Field declaredField : containerClazz.getDeclaredFields()) {
                if (declaredField.isAnnotationPresent(RegisterContainer.Supplier.class)) {
                    int modifiers = declaredField.getModifiers();
                    if (!Modifier.isStatic(modifiers)) {
                        LOGGER.error("Cannot access non-static container supplier " + declaredField.getName() + " in " + containerClazz.getSimpleName());
                        return;
                    }
                    if (!Modifier.isFinal(modifiers)) {
                        LOGGER.warn("Container supplier " + declaredField.getName() + " not final in" + containerClazz.getSimpleName());
                    }
                    if (!ContainerSupplier.class.isAssignableFrom(declaredField.getType())) {
                        LOGGER.error("Supplier annotation found on non-ContainerSupplier field " + declaredField.getName() + " in " + containerClazz.getSimpleName());
                        continue;
                    }
                    if (supplier != null) {
                        LOGGER.error("Duplicate suppliers for container " + containerClazz.getSimpleName());
                        continue;
                    }
                    declaredField.setAccessible(true);
                    try {
                        supplier = (ContainerSupplier) declaredField.get(null);
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                        continue;
                    }
                    if (supplier == null) {
                        LOGGER.error("Container supplier field " + declaredField.getName() + " null in " + containerClazz.getSimpleName());
                    }
                }
            }
            if (supplier == null) {
                LOGGER.error("No supplier found for container " + containerClazz.getSimpleName());
                return;
            }
            
            ContainerSupplier finalSupplier = supplier;
            containerTypeArray[0] = IForgeMenuType.create((windowId, playerInventory, data) -> finalSupplier.create(windowId, data.readBlockPos(), playerInventory.player));
            
            for (Field declaredField : containerClazz.getDeclaredFields()) {
                if (declaredField.isAnnotationPresent(RegisterContainer.Type.class)) {
                    if (!declaredField.getType().isAssignableFrom(MenuType.class)) {
                        LOGGER.error("Unassignable type variable " + declaredField.getName() + " in " + containerClazz.getSimpleName());
                        continue;
                    }
                    if (!Modifier.isStatic(declaredField.getModifiers())) {
                        LOGGER.error("Cannot set non-static type variable " + declaredField.getName() + " in " + containerClazz.getSimpleName());
                        continue;
                    }
                    declaredField.setAccessible(true);
                    try {
                        declaredField.set(null, containerTypeArray[0]);
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }
            }
            
            MenuType<?> type = containerTypeArray[0];
            if (type == null) {
                return;
            }
            containerRegistryEvent.register(new ResourceLocation(registryName), type);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Container registered: " + registryName);
            }
        });
    }
    
    private static final Field tileProducerTYPEField;
    
    static {
        try {
            tileProducerTYPEField = RegisterTile.Producer.class.getDeclaredField("TYPE");
            tileProducerTYPEField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }
    
    private void registerTileAnnotation(String modNamespace, Class<?> declaringClass, final String memberName) {
        if (declaringClass.isAnnotationPresent(IgnoreRegistration.class)) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Registration of tile at " + memberName + " in " + declaringClass.getName() + " ignored");
            }
            return;
        }
        
        tileRegistrationQueue.enqueue(() -> {
            
            final Field field;
            final RegisterTile annotation;
            final RegisterTile.Producer<?> producer;
            
            try {
                field = declaringClass.getDeclaredField(memberName);
                if (!field.isAnnotationPresent(RegisterTile.class)) {
                    LOGGER.error("Schrodinger's annotation on field " + memberName + " in " + declaringClass.getSimpleName());
                    return;
                }
                annotation = field.getAnnotation(RegisterTile.class);
                if (field.isAnnotationPresent(IgnoreRegistration.class)) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Registration of tile at " + memberName + " in " + declaringClass.getName() + " ignored");
                    }
                    return;
                }
                field.setAccessible(true);
                var producerObject = field.get(null);
                if (producerObject == null) {
                    LOGGER.error("Null supplier for tile field " + memberName + " in " + declaringClass.getSimpleName());
                    return;
                }
                if (producerObject.getClass() != RegisterTile.Producer.class) {
                    LOGGER.error("Attempt to register non-TileProducer BlockEntitySupplier " + memberName + " in " + declaringClass.getSimpleName());
                    return;
                }
                producer = (RegisterTile.Producer<?>) producerObject;
            } catch (NoSuchFieldException e) {
                LOGGER.error("Unable to find supplier field for tile " + memberName + " in " + declaringClass.getSimpleName());
                return;
            } catch (IllegalAccessException e) {
                LOGGER.error("Unable to access supplier field for tile " + memberName + " in " + declaringClass.getSimpleName());
                return;
            }
            
            String modid = annotation.modid();
            if (modid.equals("")) {
                modid = modNamespace;
            }
            String name = annotation.value();
            if (modid.equals("")) {
                LOGGER.error("Unable to register tile without a name from " + memberName + " in " + declaringClass.getSimpleName());
                return;
            }
            final String registryName = modid + ":" + name;
            
            // this is safe, surely
            // should actually be, otherwise previous checks should have errored
            // i was wrong, sorta, i do need to check that this is the correct type
            // TODO: check this type, somehow
            Class<?> tileClass = (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
            
            LinkedList<Block> blocks = tileBlocks.remove(tileClass);
            
            if (blocks == null) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("TileEntity has no blocks, ignoring registration: " + registryName);
                }
                return;
            }
            
            // fuck you java, its the correct size here
            @SuppressWarnings({"ConstantConditions", "ToArrayCallWithZeroLengthArrayArgument"})
            BlockEntityType<?> type = BlockEntityType.Builder.of(producer, blocks.toArray(new Block[blocks.size()])).build(null);
            
            try {
                tileProducerTYPEField.set(producer, type);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Tile entity type unable to be saved for " + memberName + " in " + declaringClass.getSimpleName());
            }
            
            tileRegistryEvent.register(new ResourceLocation(registryName), type);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("TileEntity registered: " + registryName);
            }
        });
    }
    
    private void registerCapabilityAnnotation(String modNamespace, Class<?> declaringClass, final String memberName) {
        // TODO: ignore registration
        try {
            final var field = declaringClass.getDeclaredField(memberName);
            
            // this is literally how the capability token type is defined, so, yes, this is safe
            final var capabilityClass = (Class<?>) ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
            
            registerCapabilityQueue.enqueueUntracked(() -> {
                registerCapabilitiesEvent.register(capabilityClass);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Capability registered: " + capabilityClass.getName());
                }
            });
            
        } catch (NoSuchFieldException e) {
            LOGGER.error("Failed to register capability field " + memberName + " in " + declaringClass.getSimpleName());
        }
    }

//    private void registerWorldGenAnnotation(String modNamespace, Class<?> oreClazz, final String memberName) {
//        commonSetupQueue.enqueue(() -> {
//            final Block oreInstance;
//            try {
//                final Field field = oreClazz.getDeclaredField(memberName);
//                if (field.isAnnotationPresent(IgnoreRegistration.class)) {
//                    return;
//                }
//                field.setAccessible(true);
//                oreInstance = (Block) field.get(null);
//            } catch (NoSuchFieldException e) {
//                LOGGER.error("Unable to find block field for block " + memberName);
//                return;
//            } catch (IllegalAccessException e) {
//                LOGGER.error("Unable to access block field for block " + memberName);
//                return;
//            }
//
//            final ResourceLocation resourceLocation = ForgeRegistries.BLOCKS.getKey(oreInstance);
//            assert resourceLocation != null;
//
//            if (!(oreInstance instanceof IPhosphophylliteOre oreInfo)) {
//                LOGGER.error("Attempt to register non-IPhosphophylliteOre block for world generation");
//                return;
//            }
//
//            final var targetBlockStates = new ArrayList<OreConfiguration.TargetBlockState>();
//
//            final var blockstate = oreInstance.defaultBlockState();
//
//            if (oreInfo.isNetherOre()) {
//                targetBlockStates.add(OreConfiguration.target(OreFeatures.NETHER_ORE_REPLACEABLES, blockstate));
//            } else {
//                final var stoneVariant = oreInfo.stoneVariant();
//                if(stoneVariant != null){
//                    targetBlockStates.add(OreConfiguration.target(OreFeatures.STONE_ORE_REPLACEABLES, stoneVariant.defaultBlockState()));
//                }
//                final var deepslateVariant = oreInfo.deepslateVariant();
//                if (deepslateVariant != null) {
//                    targetBlockStates.add(OreConfiguration.target(OreFeatures.DEEPSLATE_ORE_REPLACEABLES, deepslateVariant.defaultBlockState()));
//                }
//            }
//
//            final var oreConfiguration = new OreConfiguration(ImmutableList.copyOf(targetBlockStates), oreInfo.size());
//
//            final ConfiguredFeature<?, ?> configuredFeature = new ConfiguredFeature<>(Feature.ORE, oreConfiguration);
//
//            final HashSet<String> spawnBiomes = new HashSet<>(Arrays.asList(oreInfo.spawnBiomes()));
//            commonSetupEvent.enqueueWork(() -> net.minecraft.core.Registry.register(BuiltinRegistries.CONFIGURED_FEATURE, resourceLocation, configuredFeature));
//
//            final var placementModifiers = new ArrayList<PlacementModifier>();
//            if (!oreInfo.doSpawn()) {
//                placementModifiers.add(BlockPredicateFilter.forPredicate(BlockPredicate.not(BlockPredicate.alwaysTrue())));
//            }
//            final var rangeLower = oreInfo.minLevel() == Integer.MIN_VALUE ? VerticalAnchor.BOTTOM : VerticalAnchor.absolute(oreInfo.minLevel());
//            placementModifiers.addAll(OrePlacements.orePlacement(
//                    CountPlacement.of(oreInfo.count()),
//                    HeightRangePlacement.uniform(rangeLower, VerticalAnchor.absolute(oreInfo.maxLevel()))
//            ));
//
//            final var placedFeature = new PlacedFeature(new Holder.Direct<>(configuredFeature), ImmutableList.copyOf(placementModifiers));
//            final var placedFeatureHolder = new Holder.Direct<>(placedFeature);
//
//            commonSetupEvent.enqueueWork(() -> net.minecraft.core.Registry.register(BuiltinRegistries.PLACED_FEATURE, resourceLocation, placedFeature));
//
//            biomeLoadingEventHandlers.add(() -> {
//                if ((biomeLoadingEvent.getCategory() == Biome.BiomeCategory.NETHER) != oreInfo.isNetherOre()) {
//                    return;
//                }
//                if (spawnBiomes.size() > 0) {
//                    if (!spawnBiomes.contains(Objects.requireNonNull(biomeLoadingEvent.getName()).toString())) {
//                        return;
//                    }
//                }
//
//                biomeLoadingEvent.getGeneration().getFeatures(GenerationStep.Decoration.UNDERGROUND_ORES).add(placedFeatureHolder);
//            });
//        });
//    }
    
    private void registerConfigAnnotation(String modNamespace, Class<?> configClazz, final String memberName) {
        try {
            Field field = configClazz.getDeclaredField(memberName);
            if (field.isAnnotationPresent(IgnoreRegistration.class)) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Registration of config at " + memberName + " in " + configClazz.getName() + " ignored");
                }
                return;
            }
            var configObject = field.get(null);
            var annotation = field.getAnnotation(RegisterConfig.class);
            ConfigManager.registerConfig(configObject, annotation);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Config registered: " + annotation.name() + " for " + modNamespace);
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }
    }
    
    private void onModLoadAnnotation(String modNamespace, Class<?> modLoadClazz, final String memberName) {
        try {
            Method method = modLoadClazz.getDeclaredMethod(memberName.substring(0, memberName.indexOf('(')));
            if (method.isAnnotationPresent(IgnoreRegistration.class)) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Running of @OnModLoad for " + memberName + " in " + modLoadClazz.getName() + " ignored");
                }
                return;
            }
            if (!Modifier.isStatic(method.getModifiers())) {
                LOGGER.error("Cannot call non-static @OnModLoad method " + method.getName() + " in " + modLoadClazz.getSimpleName());
                return;
            }
            
            if (method.getParameterCount() != 0) {
                LOGGER.error("Cannot call @OnModLoad method with parameters " + method.getName() + " in " + modLoadClazz.getSimpleName());
                return;
            }
            
            method.setAccessible(true);
            method.invoke(null);
            
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("@OnModLoad for " + memberName + " in " + modLoadClazz.getName() + " run");
            }
            
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            e.printStackTrace();
        } catch (RuntimeException e) {
            LOGGER.warn(modLoadClazz.getName());
            e.printStackTrace();
        }
    }
}
