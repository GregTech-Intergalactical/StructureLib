/**
 * Copyright (c) 2022, glee8e This file is part of StructureLib.
 * <p>
 * StructureLib is free software; you can redistribute it and/or modify it under the terms of the GNU Lesser General
 * Public License as published by the Free Software Foundation; either version 2 of the License, or (at your option) any
 * later version.
 * <p>
 * StructureLib is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 * <p>
 * You should have received a copy of the GNU Lesser General Public License along with Foobar; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA
 */
package com.gtnewhorizon.structurelib.util;

import com.gtnewhorizon.structurelib.util.InventoryUtility.ItemStackExtractor.APIType;
import com.gtnewhorizon.structurelib.util.ItemStackPredicate.NBTMode;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * This class is part of API, but is not stable. Use at your own risk.
 */
public class InventoryUtility {

    private static final SortedRegistry<ItemStackExtractor> stackExtractors = new SortedRegistry<>();
    private static final List<Predicate<? super ServerPlayer>> enableEnder = new CopyOnWriteArrayList<>();
    /**
     * The remove() of the Iterable returned must be implemented!
     */
    private static final SortedRegistry<InventoryProvider<?>> inventoryProviders = new SortedRegistry<>();

    static {
        inventoryProviders.register("5000-main-inventory", new InventoryProvider<InventoryIterable<Inventory>>() {

            @Override
            public InventoryIterable<Inventory> getInventory(ServerPlayer player) {
                return new InventoryIterable<>(player.getInventory(), player.getInventory().getContainerSize());
            }

            @Override
            public void markDirty(InventoryIterable<Inventory> inv) {
                // player save its content using means other than inv.markDirty()
                // here we only need to sync it to client
                inv.getInventory().player.containerMenu.broadcastChanges();
            }
        });
        inventoryProviders
                .register("7000-ender-inventory", new InventoryProvider<InventoryIterable<PlayerEnderChestContainer>>() {

                    @Override
                    public InventoryIterable<PlayerEnderChestContainer> getInventory(ServerPlayer player) {
                        if (enableEnder.stream().anyMatch(p -> p.test(player)))
                            return new InventoryIterable<>(player.getEnderChestInventory());
                        return null;
                    }

                    @Override
                    public void markDirty(InventoryIterable<PlayerEnderChestContainer> inv) {
                        // inv.getInventory().markDirty();
                        // TODO this seems to be a noop
                    }
                });
    }

    public static void registerEnableEnderCondition(Predicate<? super ServerPlayer> predicate) {
        enableEnder.add(predicate);
    }

    public static void registerStackExtractor(String key, ItemStackExtractor val) {
        if (Arrays.stream(APIType.values()).noneMatch(val::isAPIImplemented))
            throw new IllegalArgumentException("Must implement at least one API");
        stackExtractors.register(key, val);
    }

    public static <Inv extends Container> void registerStackExtractor(String key,
                                                                      Function<ItemStack, ? extends Inv> extractor) {
        registerStackExtractor(key, newItemStackProvider(extractor));
    }

    public static void registerInventoryProvider(String key, InventoryProvider<?> val) {
        inventoryProviders.register(key, val);
    }

    public static <Inv extends Container> void registerInventoryProvider(String key,
            Function<ServerPlayer, ? extends Inv> extractor) {
        registerInventoryProvider(key, newInventoryProvider(extractor));
    }

    public static Iterator<? extends ItemStackExtractor> getStackExtractors() {
        return stackExtractors.iterator();
    }

    public static <Inv extends Container> InventoryProvider<InventoryIterable<Inv>> newInventoryProvider(
            Function<ServerPlayer, ? extends Inv> extractor) {
        return new InventoryProvider<InventoryIterable<Inv>>() {

            @Override
            public InventoryIterable<Inv> getInventory(ServerPlayer player) {
                Inv inv = extractor.apply(player);
                return inv != null ? new InventoryIterable<>(inv) : null;
            }

            @Override
            public void markDirty(InventoryIterable<Inv> inv) {
                //TODO figure out
                //inv.getInventory().markDirty();
            }
        };
    }

    public static ItemStackExtractor newItemStackProvider(Function<ItemStack, ? extends Container> extractor) {
        return new ItemStackExtractor() {

            @Override
            public boolean isAPIImplemented(APIType type) {
                return type == APIType.MAIN;
            }

            @Override
            public int takeFromStack(Predicate<ItemStack> predicate, boolean simulate, int count,
                    ItemStackCounter store, ItemStack stack, ItemStack filter, ServerPlayer player) {
                Container inv = extractor.apply(stack);
                if (inv == null) return 0;
                int found = takeFromInventory(
                        new InventoryIterable<>(inv),
                        predicate,
                        simulate,
                        count,
                        store,
                        filter,
                        player,
                        false);
                //TODO figure out
                if (found > 0); //inv.markDirty();
                return found;
            }
        };
    }

    /**
     * take count amount of stacks that matches given filter. might take partial amount of stuff, so simulation is
     * suggested if you ever need to take more than one.
     *
     * @param player    source of stacks
     * @param predicate item stack filter
     * @param simulate  whether to do removal
     * @param count     let's hope int size is enough...
     * @return amount taken. never negative nor bigger than count...
     */
    public static Map<ItemStack, Integer> takeFromInventory(ServerPlayer player, Predicate<ItemStack> predicate,
            boolean simulate, int count) {
        ItemStackCounterImpl store = new ItemStackCounterImpl();
        int sum = 0;
        for (InventoryProvider<?> provider : inventoryProviders) {
            sum += takeFromPlayer(player, predicate, simulate, count - sum, store, provider, null);
            if (sum >= count) return store.getStore();
        }
        return store.getStore();
    }

    /**
     * take count amount of stacks that matches given filter. might take partial amount of stuff, so simulation is
     * suggested if you ever need to take more than one.
     *
     * @param player   source of stacks
     * @param filter   the precise type of item to extract. stackSize matters
     * @param simulate whether to do removal
     * @return amount taken. never negative nor bigger than count...
     */
    public static int takeFromInventory(ServerPlayer player, ItemStack filter, boolean simulate) {
        int sum = 0;
        int count = filter.getCount();
        ItemStackPredicate predicate = ItemStackPredicate.from(filter, NBTMode.EXACT);
        ItemStackCounterImpl store = new ItemStackCounterImpl();
        for (InventoryProvider<?> provider : inventoryProviders) {
            sum += takeFromPlayer(player, predicate, simulate, count - sum, store, provider, filter);
            if (sum >= count) return sum;
        }
        return sum;
    }

    // workaround java generics issue
    private static <R extends Iterable<ItemStack>> int takeFromPlayer(ServerPlayer player,
            Predicate<ItemStack> predicate, boolean simulate, int count, ItemStackCounterImpl store,
            InventoryProvider<R> provider, ItemStack filter) {
        R inv = provider.getInventory(player);
        if (inv == null) return 0;
        int taken = takeFromInventory(inv, predicate, simulate, count, store, filter, player, true);
        if (taken > 0) provider.markDirty(inv);
        return taken;
    }

    /**
     * take count amount of stacks that matches given filter. Will do 1 level of recursion to try find more stacks....
     * e.g. from backpacks...
     *
     * @param inv       source of stacks
     * @param predicate item stack filter
     * @param simulate  whether to do removal
     * @param count     let's hope int size is enough...
     * @param recursive do recursive lookup using {@link #getStackExtractors() stack extractors}
     * @return amount taken. never negative nor bigger than count...
     */
    public static Map<ItemStack, Integer> takeFromInventory(Iterable<ItemStack> inv, Predicate<ItemStack> predicate,
            boolean simulate, int count, boolean recursive) {
        ItemStackCounterImpl store = new ItemStackCounterImpl();
        takeFromInventory(inv, predicate, simulate, count, store, null, null, recursive);
        return store.getStore();
    }

    private static int takeFromInventory(@NotNull Iterable<ItemStack> inv, @NotNull Predicate<ItemStack> predicate,
                                         boolean simulate, int count, @NotNull ItemStackCounter store, @Nullable ItemStack filter,
                                         @Nullable ServerPlayer player, boolean recursive) {
        int found = 0;
        ItemStack copiedFilter = null;
        if (filter != null) {
            copiedFilter = new ItemStack(filter.getItem(), filter.getCount());
            copiedFilter.setTag(filter.getTag());
        }
        for (Iterator<ItemStack> iterator = inv.iterator(); iterator.hasNext();) {
            ItemStack stack = iterator.next();
            // invalid stack
            if (stack.isEmpty() || stack.getCount() <= 0) continue;

            if (predicate.test(stack)) {
                found += stack.getCount();
                if (found > count) {
                    int surplus = found - count;
                    store.add(stack, stack.getCount() - surplus);
                    if (!simulate) {
                        // leave the surplus in its place
                        stack.setCount(surplus);
                    }
                    return count;
                }
                store.add(stack, stack.getCount());
                if (!simulate) iterator.remove();
                if (found == count) return count;
            }
            if (!recursive) continue;
            for (ItemStackExtractor f : stackExtractors) {
                if (filter != null && f.isAPIImplemented(APIType.EXTRACT_ONE_STACK)) {
                    copiedFilter.setCount(count - found);
                    found += f.getItem(stack, copiedFilter, simulate, player);
                } else {
                    found += f.takeFromStack(predicate, simulate, count - found, store, stack, filter, player);
                }
                if (found >= count) return found;
            }
        }
        return found;
    }

    public interface OptimizedExtractor {

        /**
         * Extract a particular type of item. The extractor can choose to not return all items contained within this
         * item as long as it makes sense, but the author should inform the player of this.
         * <p>
         * Whether optimized extractor do recursive extraction is at the discretion of implementor.
         *
         * @param source    from where an extraction should be attempted
         * @param toExtract stack to extract. should not be mutated! match the NBT tag using EXACT mode.
         * @param simulate  true if only query but does not actually remove
         * @param player    executor of extraction, or null if from a machine
         * @return amount extracted
         */
        int extract(ItemStack source, ItemStack toExtract, boolean simulate, ServerPlayer player);
    }

    public interface InventoryProvider<R extends Iterable<ItemStack>> {

        R getInventory(ServerPlayer player);

        void markDirty(R inv);
    }

    public interface ItemStackExtractor {

        enum APIType {
            MAIN,
            EXTRACT_ONE_STACK,
        }

        boolean isAPIImplemented(APIType type);

        /**
         * Extract a particular type of item. The extractor can choose to not return all items contained within this
         * item as long as it makes sense, but the author should inform the player of this.
         * <p>
         * Whether this method does recursive extraction is at the discretion of implementor.
         *
         * @param predicate the main filtering predicate. never null. It's assumed predicate always returns true on
         *                  filter, if that is not null
         * @param store     where to store extracted items. Should increment the
         * @param source    from where an extraction should be attempted
         * @param filter    stack to extract. should not be mutated! match the NBT tag using EXACT mode. might be null.
         * @param simulate  true if only query but does not actually remove
         * @param player    executor of extraction, or null if from a machine
         * @return amount extracted, or -1 if this is not implemented
         */
        int takeFromStack(Predicate<ItemStack> predicate, boolean simulate, int count, ItemStackCounter store,
                ItemStack source, ItemStack filter, ServerPlayer player);

        /**
         * Extract a particular type of item. The extractor can choose to not return all items contained within this
         * item as long as it makes sense, but the author should inform the player of this.
         * <p>
         * Whether this method does recursive extraction is at the discretion of implementor.
         *
         * @param source    from where an extraction should be attempted
         * @param toExtract stack to extract. should not be mutated! match the NBT tag using EXACT mode.
         * @param simulate  true if only query but does not actually remove
         * @param player    executor of extraction, or null if from a machine
         * @return amount extracted, or -1 if this is not implemented
         */
        default int getItem(ItemStack source, ItemStack toExtract, boolean simulate, ServerPlayer player) {
            return -1;
        }

        static ItemStackExtractor createOnlyOptimized(@NotNull OptimizedExtractor optimizedExtractor) {
            return new ItemStackExtractor() {

                @Override
                public boolean isAPIImplemented(APIType type) {
                    return type == APIType.EXTRACT_ONE_STACK;
                }

                @Override
                public int takeFromStack(Predicate<ItemStack> predicate, boolean simulate, int count,
                        ItemStackCounter store, ItemStack stack, ItemStack filter, ServerPlayer player) {
                    return 0;
                }

                @Override
                public int getItem(ItemStack source, ItemStack toExtract, boolean simulate, ServerPlayer player) {
                    return optimizedExtractor.extract(source, toExtract, simulate, player);
                }
            };
        }
    }

    public interface ItemStackCounter {

        /**
         * Add some amount of stack. Note stackSize is used instead of stack.getCount().
         */
        void add(ItemStack stack, int stackSize);
    }

    private static class ItemStackCounterImpl implements ItemStackCounter {

        private final Map<ItemStack, Integer> store = new ItemStackMap<>(true);

        @Override
        public void add(ItemStack stack, int stackSize) {
            if (stack.isEmpty() || stackSize <= 0) throw new IllegalArgumentException();
            store.merge(stack, stackSize, Integer::sum);
        }

        public Map<ItemStack, Integer> getStore() {
            return store;
        }
    }
}
