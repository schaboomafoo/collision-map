package com.collisionmap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Singleton;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;

/**
 * Same-plane doors, gates, crossings and shortcuts, loaded from transports.tsv.
 *
 * <p>The table is derived from the Shortest Path plugin's transport data (BSD 2-Clause, see
 * transports.NOTICE.txt). Rows that change plane or travel more than 52 tiles were dropped, since a
 * flood fill on one plane can't use them anyway.
 */
@Slf4j
@Singleton
public class TransportData
{
    public static final class Transport
    {
        public final int ox, oy, plane, dx, dy;
        public final String option, target;
        public final int objectId;
        private final Skill[] skills;
        private final int[] levels;

        Transport(int ox, int oy, int plane, int dx, int dy, String option, String target, int objectId,
                  Skill[] skills, int[] levels)
        {
            this.ox = ox;
            this.oy = oy;
            this.plane = plane;
            this.dx = dx;
            this.dy = dy;
            this.option = option;
            this.target = target;
            this.objectId = objectId;
            this.skills = skills;
            this.levels = levels;
        }

        /** Only skill levels are checked. Quests, items and varbits are not. */
        public boolean skillsMet(Client client)
        {
            for (int i = 0; i < skills.length; i++)
            {
                if (client.getRealSkillLevel(skills[i]) < levels[i])
                {
                    return false;
                }
            }
            return true;
        }
    }

    private List<Transport> transports = Collections.emptyList();

    public List<Transport> all()
    {
        return transports;
    }

    public void load()
    {
        List<Transport> out = new ArrayList<>();
        try (InputStream in = TransportData.class.getResourceAsStream("transports.tsv"))
        {
            if (in == null)
            {
                log.warn("transports.tsv not found on the classpath, shortcut data disabled");
                return;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (line.isEmpty() || line.charAt(0) == '#')
                {
                    continue;
                }
                Transport t = parse(line);
                if (t != null)
                {
                    out.add(t);
                }
            }
        }
        catch (IOException | RuntimeException e)
        {
            log.warn("Failed to read transports.tsv", e);
        }
        transports = out;
        log.debug("Loaded {} transports", out.size());
    }

    private static Transport parse(String line)
    {
        // ox oy plane dx dy option target objectId skills
        String[] c = line.split("\t", -1);
        if (c.length < 8)
        {
            return null;
        }
        try
        {
            int ox = Integer.parseInt(c[0]), oy = Integer.parseInt(c[1]), plane = Integer.parseInt(c[2]);
            int dx = Integer.parseInt(c[3]), dy = Integer.parseInt(c[4]);
            if (ox == dx && oy == dy)
            {
                return null;
            }

            List<Skill> skills = new ArrayList<>();
            List<Integer> levels = new ArrayList<>();
            if (c.length > 8 && !c[8].isEmpty())
            {
                for (String token : c[8].split(";"))
                {
                    // "25 Agility". Anything else (e.g. "Max Quest") is ignored, i.e. treated as met.
                    String[] parts = token.trim().split(" ", 2);
                    if (parts.length != 2)
                    {
                        continue;
                    }
                    try
                    {
                        levels.add(Integer.parseInt(parts[0]));
                        skills.add(Skill.valueOf(parts[1].trim().toUpperCase()));
                    }
                    catch (IllegalArgumentException ignored)
                    {
                        if (levels.size() > skills.size())
                        {
                            levels.remove(levels.size() - 1);
                        }
                    }
                }
            }

            return new Transport(ox, oy, plane, dx, dy, c[5], c[6], Integer.parseInt(c[7]),
                skills.toArray(new Skill[0]), levels.stream().mapToInt(Integer::intValue).toArray());
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }
}
