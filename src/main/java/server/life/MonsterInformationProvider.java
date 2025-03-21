/*
 This file is part of the OdinMS Maple Story Server
 Copyright (C) 2008 ~ 2010 Patrick Huy <patrick.huy@frz.cc>
 Matthias Butz <matze@odinms.de>
 Jan Christian Meyer <vimes@odinms.de>

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License version 3
 as published by the Free Software Foundation. You may not use, modify
 or distribute this program under any other version of the
 GNU Affero General Public License.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package server.life;

import config.YamlConfig;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.ItemInformationProvider;
import tools.DatabaseConnection;
import tools.Pair;
import tools.Randomizer;
import java.util.Random;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static constants.game.GameConstants.LOOT_LIZARD_ID;
import static constants.id.ItemId.MERGE_COIN;

public class MonsterInformationProvider {
    private static final Logger log = LoggerFactory.getLogger(MonsterInformationProvider.class);
    // Author : LightPepsi

    private static final MonsterInformationProvider instance = new MonsterInformationProvider();

    public static MonsterInformationProvider getInstance() {
        return instance;
    }

    private final Map<Integer, List<MonsterDropEntry>> drops = new HashMap<>();
    private final List<MonsterGlobalDropEntry> globaldrops = new ArrayList<>();
    private final Map<Integer, List<MonsterGlobalDropEntry>> continentDrops = new HashMap<>();

    private final Map<Integer, List<Integer>> dropsChancePool = new HashMap<>();    // thanks to ronan
    private final Set<Integer> hasNoMultiEquipDrops = new HashSet<>();
    private final Map<Integer, List<MonsterDropEntry>> extraMultiEquipDrops = new HashMap<>();

    private final Map<Pair<Integer, Integer>, Integer> mobAttackAnimationTime = new HashMap<>();
    private final Map<MobSkill, Integer> mobSkillAnimationTime = new HashMap<>();

    private final Map<Integer, Pair<Integer, Integer>> mobAttackInfo = new HashMap<>();

    private final Map<Integer, Boolean> mobBossCache = new HashMap<>();
    private final Map<Integer, String> mobNameCache = new HashMap<>();


    protected MonsterInformationProvider() {
        retrieveGlobal();
    }

    private List<MonsterDropEntry> getLootLizardDrops() {
        // Create list of all possible valuable drops
        List<MonsterDropEntry> allPossibleDrops = new ArrayList<>();

        allPossibleDrops.add(new MonsterDropEntry(2049100, 500000, 1, 1, (short)0));  // Chaos Scroll
        allPossibleDrops.add(new MonsterDropEntry(2044500, 500000, 1, 1, (short)0));  // Bow ATT 60%
        allPossibleDrops.add(new MonsterDropEntry(2044600, 500000, 1, 1, (short)0));  // Crossbow ATT 60%
        allPossibleDrops.add(new MonsterDropEntry(2044700, 500000, 1, 1, (short)0));  // Claw ATT 60%
        allPossibleDrops.add(new MonsterDropEntry(2043000, 500000, 1, 1, (short)0));  // One-Handed Sword 60%
        allPossibleDrops.add(new MonsterDropEntry(2043100, 500000, 1, 1, (short)0));  // One-Handed Axe 60%
        allPossibleDrops.add(new MonsterDropEntry(2043200, 500000, 1, 1, (short)0));  // One-Handed Mace 60%
        allPossibleDrops.add(new MonsterDropEntry(2043700, 500000, 1, 1, (short)0));  // Wand Magic ATT 60%
        allPossibleDrops.add(new MonsterDropEntry(2043800, 500000, 1, 1, (short)0));  // Staff Magic ATT 60%
        allPossibleDrops.add(new MonsterDropEntry(2040804, 500000, 1, 1, (short)0));  // Gloves ATT 60%
        allPossibleDrops.add(new MonsterDropEntry(2340000, 500000, 1, 1, (short)0));  // White Scroll
        allPossibleDrops.add(new MonsterDropEntry(2049000, 500000, 1, 1, (short)0));  // Clean Slate 1%
        allPossibleDrops.add(new MonsterDropEntry(2049001, 500000, 1, 1, (short)0));  // Clean Slate 3%
        allPossibleDrops.add(new MonsterDropEntry(2049002, 500000, 1, 1, (short)0));  // Clean Slate 5%

        List<MonsterDropEntry> customDrops = new ArrayList<>();
        int numberOfDrops = 1+ Randomizer.nextInt(allPossibleDrops.size()-1);
        while (numberOfDrops > 0)
        {
            numberOfDrops--;
            customDrops.add(allPossibleDrops.get(Randomizer.nextInt(allPossibleDrops.size())));
        }


        customDrops.add(new MonsterDropEntry(ItemId.HAPPY_BIRTHDAY, 1000000, 1, 1, (short)0));  // +1 potion)
        Random random = new Random();
        int dropCount = random.nextInt(5) + 1; // Random number between 1 and 5
        for (int i = 0; i < dropCount; i++) {
            customDrops.add(new MonsterDropEntry(MERGE_COIN, 100000, 1, 1, (short)0));
        }
        return customDrops;
    }

    public final List<MonsterGlobalDropEntry> getRelevantGlobalDrops(int mapId) {
        final int continentId = mapId / 100000000;
        return continentDrops.computeIfAbsent(continentId, this::loadContinentDrops);
    }

    private List<MonsterGlobalDropEntry> loadContinentDrops(int continentId) {
        return globaldrops.stream()
                .filter(dropEntry -> dropEntry.continentid < 0 || dropEntry.continentid == continentId)
                .toList();
    }

    private void retrieveGlobal() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT * FROM drop_data_global WHERE chance > 0");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                globaldrops.add(new MonsterGlobalDropEntry(
                        rs.getInt("itemid"),
                        rs.getInt("chance"),
                        rs.getByte("continent"),
                        rs.getInt("minimum_quantity"),
                        rs.getInt("maximum_quantity"),
                        rs.getShort("questid")));
            }
        } catch (SQLException e) {
            log.error("Error retrieving global drops", e);
        }
    }

    public List<MonsterDropEntry> retrieveEffectiveDrop(final int monsterId) {
        // this reads the drop entries searching for multi-equip, properly processing them

        List<MonsterDropEntry> list = retrieveDrop(monsterId);
        if (hasNoMultiEquipDrops.contains(monsterId) || !YamlConfig.config.server.USE_MULTIPLE_SAME_EQUIP_DROP) {
            return list;
        }

        List<MonsterDropEntry> multiDrops = extraMultiEquipDrops.get(monsterId), extra = new LinkedList<>();
        if (multiDrops == null) {
            multiDrops = new LinkedList<>();

            for (MonsterDropEntry mde : list) {
                if (ItemConstants.isEquipment(mde.itemId) && mde.Maximum > 1) {
                    multiDrops.add(mde);

                    int rnd = Randomizer.rand(mde.Minimum, mde.Maximum);
                    for (int i = 0; i < rnd - 1; i++) {
                        extra.add(mde);   // this passes copies of the equips' MDE with min/max quantity > 1, but idc on equips they are unused anyways
                    }
                }
            }

            if (!multiDrops.isEmpty()) {
                extraMultiEquipDrops.put(monsterId, multiDrops);
            } else {
                hasNoMultiEquipDrops.add(monsterId);
            }
        } else {
            for (MonsterDropEntry mde : multiDrops) {
                int rnd = Randomizer.rand(mde.Minimum, mde.Maximum);
                for (int i = 0; i < rnd - 1; i++) {
                    extra.add(mde);
                }
            }
        }

        List<MonsterDropEntry> ret = new LinkedList<>(list);
        ret.addAll(extra);

        return ret;
    }

    public final List<MonsterDropEntry> retrieveDrop(final int monsterId) {
        if (monsterId == LOOT_LIZARD_ID){
            return getLootLizardDrops();
        }
        if (drops.containsKey(monsterId)) {
            return drops.get(monsterId);
        }
        final List<MonsterDropEntry> ret = new LinkedList<>();

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT itemid, chance, minimum_quantity, maximum_quantity, questid FROM drop_data WHERE dropperid = ?")) {
            ps.setInt(1, monsterId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ret.add(new MonsterDropEntry(rs.getInt("itemid"), rs.getInt("chance"), rs.getInt("minimum_quantity"), rs.getInt("maximum_quantity"), rs.getShort("questid")));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return ret;
        }

        drops.put(monsterId, ret);
        return ret;
    }

    public final List<Integer> retrieveDropPool(final int monsterId) {  // ignores Quest and Party Quest items
        if (dropsChancePool.containsKey(monsterId)) {
            return dropsChancePool.get(monsterId);
        }

        ItemInformationProvider ii = ItemInformationProvider.getInstance();

        List<MonsterDropEntry> dropList = retrieveDrop(monsterId);
        List<Integer> ret = new ArrayList<>();

        int accProp = 0;
        for (MonsterDropEntry mde : dropList) {
            if (!ii.isQuestItem(mde.itemId) && !ii.isPartyQuestItem(mde.itemId)) {
                accProp += mde.chance;
            }

            ret.add(accProp);
        }

        if (accProp == 0) {
            ret.clear();    // don't accept mobs dropping no relevant items
        }
        dropsChancePool.put(monsterId, ret);
        return ret;
    }

    public final void setMobAttackAnimationTime(int monsterId, int attackPos, int animationTime) {
        mobAttackAnimationTime.put(new Pair<>(monsterId, attackPos), animationTime);
    }

    public final Integer getMobAttackAnimationTime(int monsterId, int attackPos) {
        Integer time = mobAttackAnimationTime.get(new Pair<>(monsterId, attackPos));
        return time == null ? 0 : time;
    }

    public final void setMobSkillAnimationTime(MobSkill skill, int animationTime) {
        mobSkillAnimationTime.put(skill, animationTime);
    }

    public final Integer getMobSkillAnimationTime(MobSkill skill) {
        Integer time = mobSkillAnimationTime.get(skill);
        return time == null ? 0 : time;
    }

    public final void setMobAttackInfo(int monsterId, int attackPos, int mpCon, int coolTime) {
        mobAttackInfo.put((monsterId << 3) + attackPos, new Pair<>(mpCon, coolTime));
    }

    public final Pair<Integer, Integer> getMobAttackInfo(int monsterId, int attackPos) {
        if (attackPos < 0 || attackPos > 7) {
            return null;
        }
        return mobAttackInfo.get((monsterId << 3) + attackPos);
    }

    public static ArrayList<Pair<Integer, String>> getMobsIDsFromName(String search) {
        DataProvider dataProvider = DataProviderFactory.getDataProvider(WZFiles.STRING);
        ArrayList<Pair<Integer, String>> retMobs = new ArrayList<>();
        Data data = dataProvider.getData("Mob.img");
        List<Pair<Integer, String>> mobPairList = new LinkedList<>();
        for (Data mobIdData : data.getChildren()) {
            int mobIdFromData = Integer.parseInt(mobIdData.getName());
            String mobNameFromData = DataTool.getString(mobIdData.getChildByPath("name"), "NO-NAME");
            mobPairList.add(new Pair<>(mobIdFromData, mobNameFromData));
        }
        for (Pair<Integer, String> mobPair : mobPairList) {
            if (mobPair.getRight().toLowerCase().contains(search.toLowerCase())) {
                retMobs.add(mobPair);
            }
        }
        return retMobs;
    }

    public boolean isBoss(int id) {
        Boolean boss = mobBossCache.get(id);
        if (boss == null) {
            try {
                boss = LifeFactory.getMonster(id).isBoss();
            } catch (NullPointerException npe) {
                boss = false;
            } catch (Exception e) {   //nonexistant mob
                boss = false;

                log.warn("Non-existent mob id {}", id, e);
            }

            mobBossCache.put(id, boss);
        }

        return boss;
    }

    public String getMobNameFromId(int id) {
        String mobName = mobNameCache.get(id);
        if (mobName == null) {
            DataProvider dataProvider = DataProviderFactory.getDataProvider(WZFiles.STRING);
            Data mobData = dataProvider.getData("Mob.img");

            mobName = DataTool.getString(mobData.getChildByPath(id + "/name"), "");
            mobNameCache.put(id, mobName);
        }

        return mobName;
    }

    public final void clearDrops() {
        drops.clear();
        hasNoMultiEquipDrops.clear();
        extraMultiEquipDrops.clear();
        dropsChancePool.clear();
        globaldrops.clear();
        continentDrops.clear();
        retrieveGlobal();
    }
}
