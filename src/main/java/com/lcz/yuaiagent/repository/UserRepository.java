package com.lcz.yuaiagent.repository;

import com.lcz.yuaiagent.model.UserRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class UserRepository {
    private final JdbcTemplate jdbcTemplate;


    public Optional<UserRow> findByUsername(String userName){
        List<UserRow> rows = jdbcTemplate.query("select id, password_hash from t_user where username = ?",
                (rs, rowNum) -> new UserRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("password_hash")
                ),
                userName);
        return rows.stream().findFirst();

    }

}
