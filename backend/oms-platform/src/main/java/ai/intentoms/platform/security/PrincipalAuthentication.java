package ai.intentoms.platform.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

public class PrincipalAuthentication extends AbstractAuthenticationToken {
    private final Principal principal;

    public PrincipalAuthentication(Principal principal) {
        super(authorities(principal));
        this.principal = principal;
        setAuthenticated(true);
    }

    private static List<GrantedAuthority> authorities(Principal p) {
        return p.roles().stream().<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
    }

    public Principal principal() { return principal; }
    @Override public Object getCredentials() { return ""; }
    @Override public Object getPrincipal() { return principal; }
}
