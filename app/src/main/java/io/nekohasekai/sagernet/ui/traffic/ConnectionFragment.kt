package io.nekohasekai.sagernet.ui.traffic

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.Connection
import io.nekohasekai.sagernet.databinding.LayoutConnectionBinding
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.ToolbarFragment
import libcore.Libcore

class ConnectionFragment(private val conn: Connection) :
    ToolbarFragment(R.layout.layout_connection),
    Toolbar.OnMenuItemClickListener {

    private lateinit var binding: LayoutConnectionBinding

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding = LayoutConnectionBinding.bind(view)
        toolbar.apply {
            setTitle(conn.uuid)
            inflateMenu(R.menu.connection_menu)
            setOnMenuItemClickListener(this@ConnectionFragment)

            setNavigationIcon(R.drawable.baseline_arrow_back_24)
            setNavigationOnClickListener {
                parentFragmentManager.popBackStack()
            }
        }

        bind()
    }

    private fun bind() {
        binding.connNetwork.text = conn.network.uppercase()
        binding.connTime.text = conn.start
        binding.connIPVersion.text = conn.ipVersion
        binding.connUpload.text = Libcore.formatBytes(conn.uploadTotal)
        binding.connDownload.text = Libcore.formatBytes(conn.downloadTotal)
        binding.connInbound.text = conn.inbound
        binding.connSource.text = conn.src
        binding.connDestination.text = conn.dst
        if (conn.host.isNotBlank()) {
            binding.connHostLayout.isVisible = true
            binding.connHost.text = conn.host
        } else {
            binding.connHostLayout.isVisible = false
        }
        binding.connRule.text = conn.matchedRule
        binding.connOutbound.text = conn.outbound
        binding.connChain.text = conn.chain
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_close_connection -> {
                (requireActivity() as? MainActivity)?.apply {
                    connection.service?.closeConnection(conn.uuid)
                    supportFragmentManager.popBackStack()
                }
                return true
            }
        }
        return false
    }
}