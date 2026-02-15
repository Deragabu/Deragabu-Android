package com.limelight.grid;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.material.chip.Chip;
import com.limelight.PcView;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.Collections;
import java.util.Comparator;

public class PcGridAdapter extends GenericGridAdapter<PcView.ComputerObject> {

    public interface CardActionListener {
        void onRefreshClick(PcView.ComputerObject computer);
        void onWakeClick(PcView.ComputerObject computer);
        void onDeleteClick(PcView.ComputerObject computer);
    }

    private CardActionListener actionListener;

    public PcGridAdapter(Context context, PreferenceConfiguration prefs) {
        super(context, getLayoutIdForPreferences(prefs));
    }

    public void setCardActionListener(CardActionListener listener) {
        this.actionListener = listener;
    }

    private static int getLayoutIdForPreferences(PreferenceConfiguration prefs) {
        return R.layout.pc_grid_item;
    }

    public void updateLayoutWithPreferences(Context context, PreferenceConfiguration prefs) {
        // This will trigger the view to reload with the new layout
        setLayoutId(getLayoutIdForPreferences(prefs));
    }

    public void addComputer(PcView.ComputerObject computer) {
        itemList.add(computer);
        sortList();
    }

    private void sortList() {
        Collections.sort(itemList, new Comparator<PcView.ComputerObject>() {
            @Override
            public int compare(PcView.ComputerObject lhs, PcView.ComputerObject rhs) {
                return lhs.details.name.toLowerCase().compareTo(rhs.details.name.toLowerCase());
            }
        });
    }

    public boolean removeComputer(PcView.ComputerObject computer) {
        return itemList.remove(computer);
    }

    @Override
    public void populateView(View parentView, ImageView imgView, ProgressBar prgView, TextView txtView, ImageView overlayView, PcView.ComputerObject obj) {
        // Set computer icon
        imgView.setImageResource(R.drawable.ic_computer);

        // Get new UI elements
        Chip statusChip = parentView.findViewById(R.id.status_chip);
        Chip gameRunningChip = parentView.findViewById(R.id.game_running_chip);
        TextView gpuText = parentView.findViewById(R.id.gpu_text);
        TextView addressText = parentView.findViewById(R.id.address_text);
        ImageButton refreshButton = parentView.findViewById(R.id.refresh_button);
        ImageButton wakeButton = parentView.findViewById(R.id.wake_button);
        ImageButton deleteButton = parentView.findViewById(R.id.delete_button);

        // Set computer name
        txtView.setText(obj.details.name);

        // Configure status chip
        if (statusChip != null) {
            if (obj.details.state == ComputerDetails.State.ONLINE) {
                imgView.setAlpha(1.0f);
                txtView.setAlpha(1.0f);
                if (obj.details.pairState == PairingManager.PairState.PAIRED) {
                    statusChip.setText(R.string.pcview_menu_header_online);
                    statusChip.setChipBackgroundColor(ColorStateList.valueOf(0xFF4CAF50)); // Green
                    statusChip.setTextColor(0xFFFFFFFF);
                } else {
                    statusChip.setText(R.string.pcview_status_not_paired);
                    statusChip.setChipBackgroundColor(ColorStateList.valueOf(0xFFFF9800)); // Orange
                    statusChip.setTextColor(0xFFFFFFFF);
                }
            } else if (obj.details.state == ComputerDetails.State.OFFLINE) {
                imgView.setAlpha(0.4f);
                txtView.setAlpha(0.6f);
                statusChip.setText(R.string.pcview_menu_header_offline);
                statusChip.setChipBackgroundColor(ColorStateList.valueOf(0xFF757575)); // Gray
                statusChip.setTextColor(0xFFFFFFFF);
            } else {
                imgView.setAlpha(0.6f);
                txtView.setAlpha(0.6f);
                statusChip.setText(R.string.pcview_status_connecting);
                statusChip.setChipBackgroundColor(ColorStateList.valueOf(0xFF607D8B)); // Blue-gray
                statusChip.setTextColor(0xFFFFFFFF);
            }
        }

        // Show/hide loading spinner
        if (obj.details.state == ComputerDetails.State.UNKNOWN) {
            prgView.setVisibility(View.VISIBLE);
        } else {
            prgView.setVisibility(View.GONE);
        }

        // Show game running chip if a game is running
        if (gameRunningChip != null) {
            if (obj.details.runningGameId != 0 && obj.details.state == ComputerDetails.State.ONLINE) {
                gameRunningChip.setVisibility(View.VISIBLE);
            } else {
                gameRunningChip.setVisibility(View.GONE);
            }
        }

        // Show GPU info if available (not available in current ComputerDetails)
        if (gpuText != null) {
            gpuText.setVisibility(View.GONE);
        }

        // Show IP address if available
        if (addressText != null) {
            String address = getActiveAddress(obj.details);
            if (address != null && !address.isEmpty()) {
                addressText.setText(address);
                addressText.setVisibility(View.VISIBLE);
            } else {
                addressText.setVisibility(View.GONE);
            }
        }

        // Setup action buttons
        if (refreshButton != null) {
            refreshButton.setOnClickListener(v -> {
                if (actionListener != null) {
                    actionListener.onRefreshClick(obj);
                }
            });
        }

        if (wakeButton != null) {
            wakeButton.setOnClickListener(v -> {
                if (actionListener != null) {
                    actionListener.onWakeClick(obj);
                }
            });
            // Disable wake button if no MAC address
            boolean hasMac = obj.details.macAddress != null && !obj.details.macAddress.isEmpty();
            wakeButton.setEnabled(hasMac);
            wakeButton.setAlpha(hasMac ? 1.0f : 0.3f);
        }

        if (deleteButton != null) {
            deleteButton.setOnClickListener(v -> {
                if (actionListener != null) {
                    actionListener.onDeleteClick(obj);
                }
            });
        }

        // Hide overlay view (not used in new design)
        overlayView.setVisibility(View.GONE);
    }

    private String getActiveAddress(ComputerDetails details) {
        if (details.activeAddress != null) {
            return details.activeAddress.address;
        } else if (details.localAddress != null) {
            return details.localAddress.address;
        } else if (details.remoteAddress != null) {
            return details.remoteAddress.address;
        }
        return null;
    }
}
