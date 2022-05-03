package im.angry.openeuicc.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.truphone.lpa.LocalProfileInfo
import com.truphone.lpa.impl.ProfileKey.*
import com.truphone.lpad.progress.Progress
import im.angry.openeuicc.R
import im.angry.openeuicc.databinding.EuiccProfileBinding
import im.angry.openeuicc.databinding.FragmentEuiccBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.Exception

class EuiccManagementFragment : Fragment(), EuiccFragmentMarker, EuiccProfilesChangedListener {
    companion object {
        const val TAG = "EuiccManagementFragment"

        fun newInstance(slotId: Int): EuiccManagementFragment =
            newInstanceEuicc(EuiccManagementFragment::class.java, slotId)
    }

    private var _binding: FragmentEuiccBinding? = null
    private val binding get() = _binding!!

    private val adapter = EuiccProfileAdapter(listOf())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentEuiccBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.swipeRefresh.setOnRefreshListener { refresh() }
        binding.profileList.adapter = adapter
        binding.profileList.layoutManager =
            LinearLayoutManager(view.context, LinearLayoutManager.VERTICAL, false)

        binding.fab.setOnClickListener {
            ProfileDownloadFragment.newInstance(slotId)
                .show(childFragmentManager, ProfileDownloadFragment.TAG)
        }
    }

    override fun onStart() {
        super.onStart()
        refresh()
    }

    override fun onEuiccProfilesChanged() {
        refresh()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refresh() {
        binding.swipeRefresh.isRefreshing = true

        lifecycleScope.launch {
            val profiles = withContext(Dispatchers.IO) {
                channel.lpa.profiles
            }

            withContext(Dispatchers.Main) {
                adapter.profiles = profiles.filter { it.profileClass != LocalProfileInfo.Clazz.Testing }
                adapter.notifyDataSetChanged()
                binding.swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun enableOrDisableProfile(iccid: String, enable: Boolean) {
        binding.swipeRefresh.isRefreshing = true
        binding.swipeRefresh.isEnabled = false
        binding.fab.isEnabled = false

        lifecycleScope.launch {
            try {
                if (enable) {
                    doEnableProfile(iccid)
                } else {
                    doDisableProfile(iccid)
                }
                Toast.makeText(context, R.string.toast_profile_enabled, Toast.LENGTH_LONG).show()
                // The APDU channel will be invalid when the SIM reboots. For now, just exit the app
                euiccChannelManager.invalidate()
                requireActivity().finish()
            } catch (e: Exception) {
                Log.d(TAG, "Failed to enable / disable profile $iccid")
                Log.d(TAG, Log.getStackTraceString(e))
                binding.fab.isEnabled = true
                binding.swipeRefresh.isEnabled = true
                Toast.makeText(context, R.string.toast_profile_enable_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun doEnableProfile(iccid: String) =
        withContext(Dispatchers.IO) {
            channel.lpa.enableProfile(iccid, Progress())
        }

    private suspend fun doDisableProfile(iccid: String) =
        withContext(Dispatchers.IO) {
            channel.lpa.disableProfile(iccid, Progress())
        }

    inner class ViewHolder(private val binding: EuiccProfileBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.iccid.setOnClickListener {
                if (binding.iccid.transformationMethod == null) {
                    binding.iccid.transformationMethod = PasswordTransformationMethod.getInstance()
                } else {
                    binding.iccid.transformationMethod = null
                }
            }

            binding.profileMenu.setOnClickListener { showOptionsMenu() }
        }

        private lateinit var profile: LocalProfileInfo

        fun setProfile(profile: LocalProfileInfo) {
            this.profile = profile
            binding.name.text = getName()

            binding.state.setText(
                if (isEnabled()) {
                    R.string.enabled
                } else {
                    R.string.disabled
                }
            )
            binding.provider.text = profile.providerName
            binding.iccid.text = profile.iccidLittleEndian
            binding.iccid.transformationMethod = PasswordTransformationMethod.getInstance()
        }

        private fun isEnabled(): Boolean =
            profile.state == LocalProfileInfo.State.Enabled

        private fun getName(): String =
            profile.nickName.ifEmpty {
                profile.name
            }

        private fun showOptionsMenu() {
            PopupMenu(binding.root.context, binding.profileMenu).apply {
                setOnMenuItemClickListener(::onMenuItemClicked)
                inflate(R.menu.profile_options)
                if (isEnabled()) {
                    menu.findItem(R.id.enable).isVisible = false
                    menu.findItem(R.id.delete).isVisible = false
                } else {
                    menu.findItem(R.id.disable).isVisible = false
                }
                show()
            }
        }

        private fun onMenuItemClicked(item: MenuItem): Boolean =
            when (item.itemId) {
                R.id.enable -> {
                    enableOrDisableProfile(profile.iccid, true)
                    true
                }
                R.id.disable -> {
                    enableOrDisableProfile(profile.iccid, false)
                    true
                }
                R.id.rename -> {
                    ProfileRenameFragment.newInstance(slotId, profile.iccid, getName())
                        .show(childFragmentManager, ProfileRenameFragment.TAG)
                    true
                }
                R.id.delete -> {
                    ProfileDeleteFragment.newInstance(slotId, profile.iccid, getName())
                        .show(childFragmentManager, ProfileDeleteFragment.TAG)
                    true
                }
                else -> false
            }
    }

    inner class EuiccProfileAdapter(var profiles: List<LocalProfileInfo>) : RecyclerView.Adapter<ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding =
                EuiccProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.setProfile(profiles[position])
        }

        override fun getItemCount(): Int = profiles.size
    }
}