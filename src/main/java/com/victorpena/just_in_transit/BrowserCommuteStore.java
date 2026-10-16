package com.victorpena.just_in_transit;
import java.util.Optional;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
@Service
public class BrowserCommuteStore {
    private final BrowserCommuteRepository records;private final BrowserIdentity browser;
    private final JsonMapper json=JsonMapper.builder().build();
    public BrowserCommuteStore(BrowserCommuteRepository records,BrowserIdentity browser) { this.records=records;this.browser=browser; }
    private String key(long corridor) { if(corridor!=1 && corridor!=2)throw new IllegalArgumentException("Unknown commute");return browser.id()+":"+corridor; }
    public Optional<SavedCommute> findById(long id) { return records.findById(key(id)).map(r->json.readValue(r.payload,SavedCommute.class)); }
    public boolean existsById(long id) { return records.existsById(key(id)); }
    public SavedCommute save(SavedCommute data) { var r=new BrowserCommute();r.id=key(data.id);r.owner=browser.id();r.payload=json.writeValueAsString(data);records.save(r);return data; }
    public SavedCommute saveAndFlush(SavedCommute data) { var r=new BrowserCommute();r.id=key(data.id);r.owner=browser.id();r.payload=json.writeValueAsString(data);records.saveAndFlush(r);return data; }
    public void deleteById(long id) { records.deleteById(key(id)); }
    public void deleteAll() { records.deleteByOwner(browser.id()); }
    public long count() { return records.countByOwner(browser.id()); }
}
