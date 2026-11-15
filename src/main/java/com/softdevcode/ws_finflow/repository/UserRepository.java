package com.softdevcode.ws_finflow.repository;

import com.softdevcode.ws_finflow.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    @Query("select u from User u where u.emailLookup = :#{@fieldCipher.lookup('users.email', #email)}")
    Optional<User> findByEmail(@Param("email") String email);

    @Query("select u from User u where u.usernameLookup = :#{@fieldCipher.lookup('users.username', #username)}")
    Optional<User> findByUsername(@Param("username") String username);
}
