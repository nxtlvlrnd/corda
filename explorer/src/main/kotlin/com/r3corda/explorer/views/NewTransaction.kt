package com.r3corda.explorer.views

import com.r3corda.client.fxutils.map
import com.r3corda.client.model.NetworkIdentityModel
import com.r3corda.client.model.NodeMonitorModel
import com.r3corda.client.model.observableList
import com.r3corda.client.model.observableValue
import com.r3corda.core.contracts.*
import com.r3corda.core.crypto.Party
import com.r3corda.core.node.NodeInfo
import com.r3corda.core.serialization.OpaqueBytes
import com.r3corda.explorer.components.ExceptionDialog
import com.r3corda.explorer.model.CashTransaction
import com.r3corda.explorer.model.IdentityModel
import com.r3corda.node.services.messaging.CordaRPCOps
import com.r3corda.node.services.messaging.TransactionBuildResult
import javafx.beans.binding.Bindings
import javafx.beans.binding.BooleanBinding
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.*
import javafx.util.StringConverter
import javafx.util.converter.BigDecimalStringConverter
import tornadofx.View
import java.math.BigDecimal
import java.util.*
import java.util.regex.Pattern

class NewTransaction : View() {
    override val root: Parent by fxml()

    private val partyATextField: TextField by fxid()
    private val partyBChoiceBox: ChoiceBox<NodeInfo> by fxid()
    private val partyALabel: Label by fxid()
    private val partyBLabel: Label by fxid()
    private val amountLabel: Label by fxid()

    private val executeButton: Button by fxid()

    private val transactionTypeCB: ChoiceBox<CashTransaction> by fxid()
    private val amount: TextField by fxid()
    private val currency: ChoiceBox<Currency> by fxid()

    private val networkIdentities: ObservableList<NodeInfo> by observableList(NetworkIdentityModel::networkIdentities)

    private val rpcProxy: ObservableValue<CordaRPCOps?> by observableValue(NodeMonitorModel::proxyObservable)
    private val myIdentity: ObservableValue<Party?> by observableValue(IdentityModel::myIdentity)
    private val notary: ObservableValue<Party?> by observableValue(IdentityModel::notary)

    private val issueRefLabel: Label by fxid()
    private val issueRefTextField: TextField by fxid()

    private fun ObservableValue<*>.isNotNull(): BooleanBinding {
        return Bindings.createBooleanBinding({ this.value != null }, arrayOf(this))
    }

    fun resetScreen() {
        partyBChoiceBox.valueProperty().set(null)
        transactionTypeCB.valueProperty().set(null)
        currency.valueProperty().set(null)
        amount.clear()
    }

    init {
        // Disable everything when not connected to node.
        val enableProperty = myIdentity.isNotNull().and(notary.isNotNull()).and(rpcProxy.isNotNull())
        root.disableProperty().bind(enableProperty.not())
        transactionTypeCB.items = FXCollections.observableArrayList(CashTransaction.values().asList())

        // Party A textfield always display my identity name, not editable.
        partyATextField.isEditable = false
        partyATextField.textProperty().bind(myIdentity.map { it?.name ?: "" })
        partyALabel.textProperty().bind(transactionTypeCB.valueProperty().map { it?.partyNameA?.let { "$it : " } })
        partyATextField.visibleProperty().bind(transactionTypeCB.valueProperty().map { it?.partyNameA }.isNotNull())

        partyBLabel.textProperty().bind(transactionTypeCB.valueProperty().map { it?.partyNameB?.let { "$it : " } })
        partyBChoiceBox.visibleProperty().bind(transactionTypeCB.valueProperty().map { it?.partyNameB }.isNotNull())
        partyBChoiceBox.items = networkIdentities

        partyBChoiceBox.converter = object : StringConverter<NodeInfo?>() {
            override fun toString(node: NodeInfo?): String {
                return node?.legalIdentity?.name ?: ""
            }

            override fun fromString(string: String?): NodeInfo {
                throw UnsupportedOperationException("not implemented")
            }
        }

        // BigDecimal text Formatter, restricting text box input to decimal values.
        val textFormatter = Pattern.compile("-?((\\d*)|(\\d+\\.\\d*))").run {
            TextFormatter<BigDecimal>(BigDecimalStringConverter(), null) { change ->
                val newText = change.controlNewText
                if (matcher(newText).matches()) change else null
            }
        }
        amount.textFormatter = textFormatter

        // Hide currency and amount fields when transaction type is not specified.
        // TODO : Create a currency model to store these values
        currency.items = FXCollections.observableList(setOf(USD, GBP, CHF).toList())
        currency.visibleProperty().bind(transactionTypeCB.valueProperty().isNotNull)
        amount.visibleProperty().bind(transactionTypeCB.valueProperty().isNotNull)
        amountLabel.visibleProperty().bind(transactionTypeCB.valueProperty().isNotNull)
        issueRefLabel.visibleProperty().bind(transactionTypeCB.valueProperty().isNotNull)
        issueRefTextField.visibleProperty().bind(transactionTypeCB.valueProperty().isNotNull)

        // Validate inputs.
        val formValidCondition = arrayOf(
                myIdentity.isNotNull(),
                transactionTypeCB.valueProperty().isNotNull,
                partyBChoiceBox.visibleProperty().not().or(partyBChoiceBox.valueProperty().isNotNull),
                textFormatter.valueProperty().isNotNull,
                textFormatter.valueProperty().isNotEqualTo(BigDecimal.ZERO),
                currency.valueProperty().isNotNull
        ).reduce(BooleanBinding::and)

        // Enable execute button when form is valid.
        executeButton.disableProperty().bind(formValidCondition.not())
        executeButton.setOnAction { event ->
            // Null checks to ensure these observable values are set, execute button should be disabled if any of these value are null, this extra checks are for precaution and getting non-nullable values without using !!.
            myIdentity.value?.let { myIdentity ->
                notary.value?.let { notary ->
                    rpcProxy.value?.let { rpcProxy ->
                        Triple(myIdentity, notary, rpcProxy)
                    }
                }
            }?.let {
                val (myIdentity, notary, rpcProxy) = it
                transactionTypeCB.value?.let {
                    val issueRef = OpaqueBytes(if (issueRefTextField.text.trim().isNotBlank()) issueRefTextField.text.toByteArray() else ByteArray(1, { 1 }))
                    val command = when (it) {
                        CashTransaction.Issue -> ClientToServiceCommand.IssueCash(Amount(textFormatter.value, currency.value), issueRef, partyBChoiceBox.value.legalIdentity, notary)
                        CashTransaction.Pay -> ClientToServiceCommand.PayCash(Amount(textFormatter.value, Issued(PartyAndReference(myIdentity, issueRef), currency.value)), partyBChoiceBox.value.legalIdentity)
                        CashTransaction.Exit -> ClientToServiceCommand.ExitCash(Amount(textFormatter.value, currency.value), issueRef)
                    }

                    val dialog = Alert(Alert.AlertType.INFORMATION).apply {
                        headerText = null
                        contentText = "Transaction Started."
                        dialogPane.isDisable = true
                        initOwner((event.target as Node).scene.window)
                    }
                    dialog.show()
                    runAsync {
                        rpcProxy.executeCommand(command)
                    }.ui {
                        dialog.contentText = when (it) {
                            is TransactionBuildResult.ProtocolStarted -> {
                                dialog.alertType = Alert.AlertType.INFORMATION
                                dialog.setOnCloseRequest { resetScreen() }
                                "Transaction Started \nTransaction ID : ${it.transaction?.id} \nMessage : ${it.message}"
                            }
                            is TransactionBuildResult.Failed -> {
                                dialog.alertType = Alert.AlertType.ERROR
                                it.toString()
                            }
                        }
                        dialog.dialogPane.isDisable = false
                        dialog.dialogPane.scene.window.sizeToScene()
                    }.setOnFailed {
                        dialog.close()
                        ExceptionDialog(it.source.exception).apply {
                            initOwner((event.target as Node).scene.window)
                            showAndWait()
                        }
                    }
                }
            }
        }
        // Remove focus from textfield when click on the blank area.
        root.setOnMouseClicked { e -> root.requestFocus() }
    }
}