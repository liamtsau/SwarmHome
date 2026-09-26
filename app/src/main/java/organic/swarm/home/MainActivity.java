package organic.swarm.home;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;

public class MainActivity extends Activity implements SwarmView.Host {

    private SwarmView swarm;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().setStatusBarColor(0xFF050306);
        getWindow().setNavigationBarColor(0xFF050306);

        swarm = new SwarmView(this, this);
        setContentView(swarm);

        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager roles = getSystemService(RoleManager.class);

            if (roles != null &&
                roles.isRoleAvailable(RoleManager.ROLE_HOME) &&
                !roles.isRoleHeld(RoleManager.ROLE_HOME)) {

                startActivityForResult(
                    roles.createRequestRoleIntent(RoleManager.ROLE_HOME), 41);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (swarm != null) swarm.refreshApps();
    }

    @Override
    public void eye() {
        try {
            startActivity(new Intent(MediaStore.ACTION_IMAGE_CAPTURE));
        } catch (Exception ignored) {}
    }

    @Override
    public void memory() {
        try {
            Intent i;

            if (Build.VERSION.SDK_INT >= 33)
                i = new Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*");
            else
                i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("image/*")
                        .addCategory(Intent.CATEGORY_OPENABLE);

            startActivity(i);
        } catch (Exception ignored) {}
    }

    @Override
    public void explore(String query) {
        String q = query == null ? "" : query.trim();

        if (q.isEmpty())
            q = "https://www.google.com";
        else if (!q.startsWith("http://") && !q.startsWith("https://"))
            q = "https://www.google.com/search?q=" +
                    android.net.Uri.encode(q);

        startActivity(new Intent(
            Intent.ACTION_VIEW,
            android.net.Uri.parse(q)));
    }

    @Override
    public void hive() {
        try {
            startActivity(new Intent(
                Intent.ACTION_PICK,
                android.provider.ContactsContract.Contacts.CONTENT_URI));
        } catch (Exception ignored) {}
    }

    @Override
    public void settings() {
        startActivity(new Intent(Settings.ACTION_SETTINGS));
    }
}
