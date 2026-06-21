package com.tca.UserManager.security; 
 
import java.util.Collection; 
import org.springframework.security.core.GrantedAuthority; 
import org.springframework.security.core.userdetails.UserDetails; 
import com.tca.UserManager.entity.User; 
 
// Adapts our User entity to the interface Spring Security expects 
public class CustomUserDetails implements UserDetails { 
 
    private static final long serialVersionUID = 1L; 
    User user;  // The actual User from our database 
 
    public CustomUserDetails(User user) { 
        this.user = user; 
    } 
 
    @Override 
    public Collection<? extends GrantedAuthority> getAuthorities() { 
        return null;  // No roles configured yet — all authenticated users have same access 
    } 
 
    @Override 
    public String getPassword() { return this.user.getPassword(); } 
 
    @Override 
    public String getUsername() { return this.user.getUsername(); } 
 
    // The following four methods control account state: 
    @Override public boolean isAccountNonExpired()     { return true; } 
    @Override public boolean isAccountNonLocked()      { return true; } 
    @Override public boolean isCredentialsNonExpired() { return true; } 
    @Override public boolean isEnabled()               { return true; } 
}