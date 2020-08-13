package com.mishiranu.dashchan.ui.preference.core;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.mishiranu.dashchan.C;
import com.mishiranu.dashchan.util.ResourceUtils;
import com.mishiranu.dashchan.util.ViewUtils;
import com.mishiranu.dashchan.widget.DividerItemDecoration;
import com.mishiranu.dashchan.widget.ViewFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;

public abstract class PreferenceFragment extends Fragment {
	private final ArrayList<Preference<?>> preferences = new ArrayList<>();
	private final HashSet<Preference<?>> persistent = new HashSet<>();
	private final ArrayList<Dependency> dependencies = new ArrayList<>();

	private RecyclerView recyclerView;

	private static abstract class Dependency {
		public final String key, dependencyKey;
		public final boolean positive;

		public Dependency(String key, String dependencyKey, boolean positive) {
			this.key = key;
			this.dependencyKey = dependencyKey;
			this.positive = positive;
		}

		public abstract boolean checkDependency(Preference<?> dependencyPreference);
	}

	private static class BooleanDependency extends Dependency {
		public BooleanDependency(String key, String dependencyKey, boolean positive) {
			super(key, dependencyKey, positive);
		}

		@Override
		public boolean checkDependency(Preference<?> dependencyPreference) {
			if (dependencyPreference instanceof CheckPreference) {
				return ((CheckPreference) dependencyPreference).getValue() == positive;
			}
			return false;
		}
	}

	private static class StringDependency extends Dependency {
		private final HashSet<String> values = new HashSet<>();

		public StringDependency(String key, String dependencyKey, boolean positive, String... values) {
			super(key, dependencyKey, positive);
			Collections.addAll(this.values, values);
		}

		@Override
		public boolean checkDependency(Preference<?> dependencyPreference) {
			String value;
			if (dependencyPreference instanceof EditPreference) {
				value = ((EditPreference) dependencyPreference).getValue();
			} else if (dependencyPreference instanceof ListPreference) {
				value = ((ListPreference) dependencyPreference).getValue();
			} else {
				return false;
			}
			return values.contains(value) == positive;
		}
	}

	protected abstract SharedPreferences getPreferences();

	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		recyclerView = new RecyclerView(container.getContext());
		recyclerView.setId(android.R.id.list);
		recyclerView.setMotionEventSplittingEnabled(false);
		recyclerView.setLayoutManager(new LinearLayoutManager(container.getContext()));
		recyclerView.setAdapter(new Adapter());
		recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(), (c, position) -> {
			Preference<?> current = preferences.get(position);
			Preference<?> next = preferences.size() > position + 1 ? preferences.get(position + 1) : null;
			boolean need = !(current instanceof HeaderPreference) &&
					(!(next instanceof HeaderPreference) || C.API_LOLLIPOP);
			if (need && C.API_LOLLIPOP) {
				need = !(current instanceof CategoryPreference) && !(next instanceof CategoryPreference);
			}
			c.configure(need);
		}));
		if (!C.API_LOLLIPOP) {
			float density = ResourceUtils.obtainDensity(recyclerView);
			recyclerView.setPadding((int) (16f * density), 0, (int) (16f * density), 0);
		}
		recyclerView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));
		return recyclerView;
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();

		preferences.clear();
		persistent.clear();
		dependencies.clear();
		recyclerView = null;
	}

	private static final Object PAYLOAD = new Object();

	private <T> void onChange(Preference<T> preference, boolean newValue) {
		if (newValue) {
			if (persistent.contains(preference)) {
				preference.persist(getPreferences());
			}
			onPreferenceAfterChange(preference);
			preference.notifyAfterChange();
		}
		int index = preferences.indexOf(preference);
		if (index >= 0) {
			recyclerView.getAdapter().notifyItemChanged(index, PAYLOAD);
		}
	}

	public <T> void addPreference(Preference<T> preference, boolean persistent) {
		preferences.add(preference);
		if (preference.key != null && persistent) {
			preference.extract(getPreferences());
			this.persistent.add(preference);
		}
		preference.setOnChangeListener(newValue -> onChange(preference, newValue));
	}

	public void movePreference(Preference<?> which, Preference<?> after) {
		int removeIndex = preferences.indexOf(which);
		if (removeIndex < 0) {
			throw new IllegalStateException();
		}
		preferences.remove(removeIndex);
		recyclerView.getAdapter().notifyItemRemoved(removeIndex);
		int index = preferences.indexOf(after) + 1;
		preferences.add(index, which);
		recyclerView.getAdapter().notifyItemInserted(index);
	}

	public <T> void addDialogPreference(Preference<T> preference) {
		addPreference(preference, true);
		preference.setOnClickListener(p -> new PreferenceDialog(p.key).show(getChildFragmentManager(),
				PreferenceDialog.class.getName()));
	}

	public int removePreference(Preference<?> preference) {
		int index = preferences.indexOf(preference);
		if (index >= 0) {
			preferences.remove(index);
			persistent.remove(preference);
			recyclerView.getAdapter().notifyItemRemoved(index);
		}
		return preferences.size();
	}

	public Preference<Void> addHeader(int titleResId) {
		Preference<Void> preference = new HeaderPreference(requireContext(), getString(titleResId));
		addPreference(preference, false);
		return preference;
	}

	public Preference<Void> addButton(int titleResId, int summaryResId) {
		return addButton(titleResId != 0 ? getString(titleResId) : null,
				summaryResId != 0 ? getString(summaryResId) : null);
	}

	public Preference<Void> addButton(CharSequence title, CharSequence summary) {
		return addButton(title, p -> summary);
	}

	public Preference<Void> addButton(CharSequence title, Preference.SummaryProvider<Void> summaryProvider) {
		Preference<Void> preference = new ButtonPreference(requireContext(), title, summaryProvider);
		addPreference(preference, false);
		return preference;
	}

	public Preference<Void> addCategory(int titleResId) {
		return addCategory(getString(titleResId), null);
	}

	public Preference<Void> addCategory(CharSequence title, Drawable icon) {
		Preference<Void> preference = new CategoryPreference(requireContext(), title, icon);
		addPreference(preference, false);
		return preference;
	}

	public CheckPreference addCheck(boolean persistent, String key, boolean defaultValue,
			int titleResId, int summaryResId) {
		return addCheck(persistent, key, defaultValue, titleResId != 0 ? getString(titleResId) : null,
				summaryResId != 0 ? getString(summaryResId) : null);
	}

	public CheckPreference addCheck(boolean persistent, String key, boolean defaultValue,
			CharSequence title, CharSequence summary) {
		CheckPreference preference = new CheckPreference(requireContext(), key, defaultValue, title, summary);
		addPreference(preference, persistent);
		preference.setOnClickListener(p -> p.setValue(!p.getValue()));
		return preference;
	}

	public EditPreference addEdit(String key, String defaultValue,
			int titleResId, CharSequence hint, int inputType) {
		return addEdit(key, defaultValue, titleResId, p -> {
			CharSequence summary = p.getValue();
			if (summary == null || summary.length() == 0) {
				summary = ((EditPreference) p).hint;
			}
			return summary;
		}, hint, inputType);
	}

	public EditPreference addEdit(String key, String defaultValue,
			int titleResId, int summaryResId, CharSequence hint, int inputType) {
		return addEdit(key, defaultValue, titleResId,
				p -> summaryResId != 0 ? getString(summaryResId) : null, hint, inputType);
	}

	public EditPreference addEdit(String key, String defaultValue,
			int titleResId, Preference.SummaryProvider<String> summaryProvider, CharSequence hint, int inputType) {
		EditPreference preference = new EditPreference(requireContext(), key, defaultValue,
				getString(titleResId), summaryProvider, hint, inputType);
		addDialogPreference(preference);
		return preference;
	}

	public int[] createInputTypes(int count, int inputType) {
		int[] inputTypes = new int[count];
		for (int i = 0; i < count; i++) {
			inputTypes[i] = inputType;
		}
		return inputTypes;
	}

	public MultipleEditTextPreference addMultipleEdit(String key, int titleResId, int summaryResId,
			CharSequence[] hints, int[] inputTypes) {
		return addMultipleEdit(key, titleResId, p -> summaryResId != 0 ? getString(summaryResId) : null,
				hints, inputTypes);
	}

	public MultipleEditTextPreference addMultipleEdit(String key, int titleResId, String summaryPattern,
			CharSequence[] hints, int[] inputTypes) {
		return addMultipleEdit(key, titleResId,
				p -> MultipleEditTextPreference.formatValues(summaryPattern, p.getValue()), hints, inputTypes);
	}

	public MultipleEditTextPreference addMultipleEdit(String key,
			int titleResId, Preference.SummaryProvider<String[]> summaryProvider,
			CharSequence[] hints, int[] inputTypes) {
		MultipleEditTextPreference preference = new MultipleEditTextPreference(requireContext(), key,
				getString(titleResId), summaryProvider, hints, inputTypes);
		addDialogPreference(preference);
		return preference;
	}

	public ListPreference addList(String key, String[] values,
			String defaultValue, int titleResId, int entriesResId) {
		return addList(key, values, defaultValue, titleResId, getResources().getStringArray(entriesResId));
	}

	public ListPreference addList(String key, String[] values,
			String defaultValue, int titleResId, CharSequence[] entries) {
		ListPreference preference = new ListPreference(requireContext(), key, defaultValue, getString(titleResId),
				entries, values);
		addDialogPreference(preference);
		return preference;
	}

	public SeekPreference addSeek(String key, int defaultValue,
			int titleResId, int summaryFormatResId, int minValue, int maxValue, int step, float multiplier) {
		return addSeek(key, defaultValue, titleResId != 0 ? getString(titleResId) : null,
				summaryFormatResId != 0 ? getString(summaryFormatResId) : null, minValue, maxValue, step, multiplier);
	}

	public SeekPreference addSeek(String key, int defaultValue,
			String title, String summaryFormat, int minValue, int maxValue, int step, float multiplier) {
		SeekPreference preference = new SeekPreference(requireContext(), key, defaultValue, title,
				summaryFormat, minValue, maxValue, step, multiplier);
		addDialogPreference(preference);
		return preference;
	}

	public void addDependency(String key, String dependencyKey, boolean positive) {
		Dependency dependency = new BooleanDependency(key, dependencyKey, positive);
		dependencies.add(dependency);
		updateDependency(dependency);
	}

	public void addDependency(String key, String dependencyKey, boolean positive, String... values) {
		Dependency dependency = new StringDependency(key, dependencyKey, positive, values);
		dependencies.add(dependency);
		updateDependency(dependency);
	}

	public Preference<?> findPreference(String key) {
		for (Preference<?> preference : preferences) {
			if (key.equals(preference.key)) {
				return preference;
			}
		}
		return null;
	}

	private void updateDependency(Dependency dependency) {
		Preference<?> dependencyPreference = findPreference(dependency.dependencyKey);
		if (dependencyPreference != null) {
			updateDependency(dependency, dependencyPreference);
		}
	}

	private void updateDependency(Dependency dependency, Preference<?> dependencyPreference) {
		Preference<?> preference = findPreference(dependency.key);
		if (preference != null) {
			preference.setEnabled(dependency.checkDependency(dependencyPreference));
		}
	}

	private void onPreferenceAfterChange(Preference<?> preference) {
		for (Dependency dependency : dependencies) {
			if (preference.key.equals(dependency.dependencyKey)) {
				updateDependency(dependency, preference);
			}
		}
	}

	public AlertDialog getDialog(Preference<?> preference) {
		getChildFragmentManager().executePendingTransactions();
		PreferenceDialog preferenceDialog = (PreferenceDialog) getChildFragmentManager()
				.findFragmentByTag(PreferenceDialog.class.getName());
		return preferenceDialog != null && preferenceDialog.getPreference() == preference
				? (AlertDialog) preferenceDialog.getDialog() : null;
	}

	private class Adapter extends RecyclerView.Adapter<Adapter.ViewHolder> {
		private class ViewHolder extends RecyclerView.ViewHolder {
			private final Preference.ViewHolder viewHolder;

			public ViewHolder(View itemView, Preference.ViewHolder viewHolder) {
				super(itemView);
				this.viewHolder = viewHolder;
				itemView.setOnClickListener(v -> {
					Preference<?> preference = preferences.get(getAdapterPosition());
					preference.performClick();
				});
				if (itemView.getBackground() == null) {
					ViewUtils.setBackgroundPreservePadding(itemView, ResourceUtils
							.getDrawable(itemView.getContext(), android.R.attr.selectableItemBackground, 0));
				}
			}
		}

		private final HashMap<Preference.ViewType, Preference<?>> viewProviders = new HashMap<>();

		@Override
		public int getItemCount() {
			return preferences.size();
		}

		@Override
		public int getItemViewType(int position) {
			Preference<?> preference = preferences.get(position);
			viewProviders.put(preference.getViewType(), preference);
			return preference.getViewType().ordinal();
		}

		@NonNull
		@Override
		public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			Preference.ViewHolder preferenceViewHolder = viewProviders
					.get(Preference.ViewType.values()[viewType]).createViewHolder(parent);
			return new ViewHolder(preferenceViewHolder.view, preferenceViewHolder);
		}

		@Override
		public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
			preferences.get(position).bindViewHolder(holder.viewHolder);
		}
	}

	private static class HeaderPreference extends Preference.Runtime<Void> {
		public HeaderPreference(Context context, CharSequence title) {
			super(context, null, null, title, null);
			setSelectable(false);
		}

		@Override
		public ViewType getViewType() {
			return ViewType.HEADER;
		}

		@Override
		public ViewHolder createViewHolder(ViewGroup parent) {
			TextView header = ViewFactory.makeListTextHeader(parent, false);
			float density = ResourceUtils.obtainDensity(parent);
			if (C.API_LOLLIPOP) {
				header.setPadding(header.getPaddingLeft(), (int) (8f * density) + header.getPaddingTop(),
						header.getPaddingRight(), header.getPaddingBottom());
			} else {
				header.setPadding((int) (8f * density), header.getPaddingTop(),
						(int) (8f * density), header.getPaddingBottom());
			}
			return new ViewHolder(header, header, null);
		}
	}

	private static class ButtonPreference extends Preference.Runtime<Void> {
		public ButtonPreference(Context context, CharSequence title, SummaryProvider<Void> summaryProvider) {
			super(context, null, null, title, summaryProvider);
		}
	}

	private static class CategoryPreference extends ButtonPreference {
		private final Drawable icon;

		public CategoryPreference(Context context, CharSequence title, Drawable icon) {
			super(context, title, null);
			this.icon = icon;
		}

		@Override
		public ViewType getViewType() {
			return ViewType.CATEGORY;
		}

		private static class IconViewHolder extends ViewHolder {
			public final ImageView icon;

			public IconViewHolder(ViewHolder viewHolder, ImageView icon) {
				super(viewHolder);
				this.icon = icon;
			}
		}

		@Override
		public ViewHolder createViewHolder(ViewGroup parent) {
			ViewHolder viewHolder = super.createViewHolder(parent);
			if (C.API_LOLLIPOP) {
				float density = ResourceUtils.obtainDensity(parent);
				ImageView icon = new ImageView(viewHolder.view.getContext());
				LinearLayout.LayoutParams layoutParams = new LinearLayout
						.LayoutParams((int) (24f * density), (int) (24f * density));
				layoutParams.setMarginEnd((int) (32f * density));
				icon.setLayoutParams(layoutParams);
				((LinearLayout) viewHolder.view).addView(icon, 0);
				return new IconViewHolder(viewHolder, icon);
			} else  {
				TypedArray typedArray = parent.getContext()
						.obtainStyledAttributes(new int[] {android.R.attr.listPreferredItemHeightSmall});
				if (typedArray.hasValue(0)) {
					viewHolder.view.setMinimumHeight(typedArray.getDimensionPixelSize(0,
							viewHolder.view.getMinimumHeight()));
				}
				typedArray.recycle();
				return viewHolder;
			}
		}

		@Override
		public void bindViewHolder(ViewHolder viewHolder) {
			super.bindViewHolder(viewHolder);

			if (viewHolder instanceof IconViewHolder) {
				IconViewHolder iconViewHolder = (IconViewHolder) viewHolder;
				iconViewHolder.icon.setImageDrawable(icon);
				iconViewHolder.icon.setVisibility(icon != null ? View.VISIBLE : View.GONE);
			}
		}
	}

	public static class PreferenceDialog extends DialogFragment {
		private static final String EXTRA_KEY = "key";

		public PreferenceDialog() {}

		public PreferenceDialog(String key) {
			Bundle args = new Bundle();
			args.putString(EXTRA_KEY, key);
			setArguments(args);
		}

		private DialogPreference<?> getPreference() {
			String key = requireArguments().getString(EXTRA_KEY);
			return (DialogPreference<?>) ((PreferenceFragment) getParentFragment()).findPreference(key);
		}

		@NonNull
		@Override
		public AlertDialog onCreateDialog(Bundle savedInstanceState) {
			return getPreference().createDialog(savedInstanceState);
		}

		@Override
		public void onSaveInstanceState(@NonNull Bundle outState) {
			super.onSaveInstanceState(outState);
			getPreference().saveState((AlertDialog) getDialog(), outState);
		}
	}
}