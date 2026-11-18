package com.noted.backend;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chan loi "migration khong bao gio chay": Flyway chi nhan file dung mau
 * V<so>__<mo ta>.sql; file sai mau (VD thieu "__") truoc day bi bo qua am tham.
 * Cung chan 2 file trung so phien ban (Flyway bao loi luc khoi dong production).
 */
class MigrationNamingTest {

    private static final Pattern VERSIONED = Pattern.compile("V(\\d+(?:[._]\\d+)*)__[A-Za-z0-9_]+\\.sql");

    @Test
    void everyMigrationFollowsFlywayNamingAndVersionsAreUnique() throws Exception {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*");
        assertThat(files).isNotEmpty();

        List<String> invalid = new ArrayList<>();
        Set<String> versions = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        for (Resource file : files) {
            String name = file.getFilename();
            Matcher matcher = VERSIONED.matcher(name == null ? "" : name);
            if (!matcher.matches()) {
                invalid.add(name);
            } else if (!versions.add(matcher.group(1).replace('_', '.'))) {
                duplicates.add(name);
            }
        }

        assertThat(invalid).as("file migration dat ten sai quy uoc Flyway").isEmpty();
        assertThat(duplicates).as("file migration trung so phien ban").isEmpty();
    }
}
