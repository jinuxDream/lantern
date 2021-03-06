package org.lantern;

import org.apache.commons.lang.StringUtils;
import org.jivesoftware.smack.RosterEntry;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.RosterPacket.Item;
import org.jivesoftware.smack.packet.RosterPacket.ItemStatus;
import org.jivesoftware.smackx.packet.VCard;
import org.littleshoot.commom.xmpp.XmppUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LanternRosterEntry implements Comparable<LanternRosterEntry> {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private boolean available;
    private boolean away;
    private String status;
    private String subscriptionStatus;
    private boolean trusted;
    private String name;
    private String email;
    
    private VCard vcard;
    
    private final int mc;
    
    private final int emc;
    
    private final int w;
    
    private final boolean rejected;
    
    private final String t;
    
    private final boolean autosub;
    
    private final String aliasFor;
    
    private final String inv;
    private final int sortKey;

    public LanternRosterEntry(final Presence pres) {
        this(pres.isAvailable(), false, pres.getFrom(), 
            pres.getFrom(), pres.getStatus(), 0, 0, 0, false, "", false, "", "");
    }
    
    public LanternRosterEntry(final String email) {
        this(false, true, email, "", "", 0, 0, 0, false, "", false, "", "");
    }
    
    public LanternRosterEntry(final RosterEntry entry) {
        this(false, false, entry.getUser(), entry.getName(),  
            extractSubscriptionStatus(entry), entry.getMc(), entry.getEmc(), entry.getW(),
            entry.isRejected(), entry.getT(), entry.isAutosub(),
            entry.getAliasFor(), entry.getInv());
    }
    
    public LanternRosterEntry(final Item entry) {
        this(false, false, entry.getUser(), entry.getName(),  
            extractSubscriptionStatus(entry), entry.getMc(), entry.getEmc(), entry.getW(),
            entry.isRejected(), entry.getT(), entry.isAutosub(),
            entry.getAliasFor(), entry.getInv());
    }

    public LanternRosterEntry(final boolean available, final boolean away, 
        final String email, final String name, final String subscriptionStatus, 
        final int mc, final int emc, final int w, final boolean rejected, 
        final String t, final boolean autosub, final String aliasFor, 
        final String inv) {
        this.available = available;
        this.away = away;
        if (StringUtils.isBlank(email)) {
            log.warn("No email address!!");
            throw new IllegalArgumentException("Blank email??");
        }
        this.email = XmppUtils.jidToUser(email);
        this.trusted = extractTrusted(email);
        this.name = name == null ? "" : name;
        this.setSubscriptionStatus(subscriptionStatus == null ? "" : subscriptionStatus);
        this.status = "";
        this.mc = mc;
        this.emc = emc;
        this.w = w;
        this.rejected = rejected;
        this.t = t == null ? "" : t;
        this.autosub = autosub;
        this.aliasFor = aliasFor == null ? "" : aliasFor;
        this.inv = inv == null ? "" : inv;
        this.sortKey = this.emc + this.mc + this.w;
    }

    private static boolean extractTrusted(final String email) {
        return LanternHub.getTrustedContactsManager().isTrusted(email);
    }


    private static String extractSubscriptionStatus(final Item entry) {
        return extractSubscriptionStatus(entry.getItemStatus());
    }
    
    private static String extractSubscriptionStatus(final RosterEntry entry) {
        final ItemStatus stat = entry.getStatus();
        return extractSubscriptionStatus(stat);
    }
    
    private static String extractSubscriptionStatus(final ItemStatus stat) {
        if (stat != null) {
            return stat.toString();
        } else {
            return "";
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public boolean isAway() {
        return away;
    }

    public void setAway(boolean away) {
        this.away = away;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(final String status) {
        if (status != null) {
            this.status = status;
        }
    }

    public void setTrusted(boolean trusted) {
        this.trusted = trusted;
    }

    public boolean isTrusted() {
        return trusted;
    }

    public void setName(final String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getEmail() {
        return email;
    }

    public boolean isInvited() {
        return LanternHub.settings().getInvited().contains(email);
    }

    public VCard getVcard() {
        return vcard;
    }

    public void setVcard(VCard vcard) {
        this.vcard = vcard;
    }
    
    public int getMc() {
        return mc;
    }

    public int getEmc() {
        return emc;
    }

    public int getW() {
        return w;
    }

    public boolean isRejected() {
        return rejected;
    }

    public String getT() {
        return t;
    }

    public boolean isAutosub() {
        return autosub;
    }

    public String getAliasFor() {
        return aliasFor;
    }

    public String getInv() {
        return inv;
    }

    public String getSubscriptionStatus() {
        return subscriptionStatus;
    }

    public void setSubscriptionStatus(String subscriptionStatus) {
        this.subscriptionStatus = subscriptionStatus;
    }
    
    public int getSortKey() {
        return sortKey;
    }
    
    @Override
    public String toString() {
        return getClass().getSimpleName()+
                " [available=" + available + ", away=" + away
                + ", status=" + status + ", trusted=" + trusted + ", name="
                + name + ", email=" + email + "]";
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((email == null) ? 0 : email.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        LanternRosterEntry other = (LanternRosterEntry) obj;
        if (email == null) {
            if (other.email != null)
                return false;
        } else if (!email.equals(other.email))
            return false;
        return true;
    }
    
    @Override
    public int compareTo(final LanternRosterEntry lre) {
        final Integer score1 = this.sortKey;
        final Integer score2 = lre.getSortKey();
        final int scores = score1.compareTo(score2);
        
        // If they have the same scores, compare by their e-mails. Otherwise
        // any entries with the same score will get consolidated.
        if (scores == 0) {
            return this.email.compareToIgnoreCase(lre.getEmail());
        } else {
            return -scores;
        }
    }
}
