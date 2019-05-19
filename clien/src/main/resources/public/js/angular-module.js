"use strict";

const app = angular.module('idpModule', ['ui.bootstrap']);

// Fix for unhandled rejections bug.
app.config(['$qProvider', function ($qProvider) {
    $qProvider.errorOnUnhandledRejections(false);
}]);

app.controller('IdpController', function($http, $location, $uibModal) {
    const idpApp = this;

    const apiBaseURL = "/api/example/";
    let peers = [];

    $http.get(apiBaseURL + "me").then((response) => idpApp.thisNode = response.data.me);

    $http.get(apiBaseURL + "peers").then((response) => peers = response.data.peers);

    idpApp.openModal = () => {
        const modalInstance = $uibModal.open({
            templateUrl: 'idpAppModal.html',
            controller: 'ModalInstanceCtrl',
            controllerAs: 'modalInstance',
            resolve: {
                idpApp: () => idpApp,
                apiBaseURL: () => apiBaseURL,
                peers: () => peers
            }
        });

        modalInstance.result.then(() => {}, () => {});
    };


       idpApp.openTransfer = () => {
            const modalTransfer = $uibModal.open({
                templateUrl: 'idpAppTransfer.html',
                controller: 'TransferCtrl',
                controllerAs: 'modalTransfer',
                resolve: {
                    idpApp: () => idpApp,
                    apiBaseURL: () => apiBaseURL,
                    peers: () => peers
                }
            });

            modalInstance.result.then(() => {}, () => {});
        };

    idpApp.getTransactions = () => $http.get(apiBaseURL + "transactions")
        .then((response) => idpApp.transactions = Object.keys(response.data)
            .map((key) => response.data[key].state.data)
            .reverse());

    /*idpApp.getMyTransactions = () => $http.get(apiBaseURL + "my-transactions")
        .then((response) => idpApp.mytransactions = Object.keys(response.data)
            .map((key) => response.data[key].state.data)
            .reverse());*/

    idpApp.getTransactions();
    //idpApp.getMyTransactions();
});

app.controller('ModalInstanceCtrl', function ($http, $location, $uibModalInstance, $uibModal, idpApp, apiBaseURL, peers) {
    const modalInstance = this;

    modalInstance.peers = peers;
    modalInstance.form = {};
    modalInstance.formError = false;

        // Validates and sends IOU.
        modalInstance.create = function validateAndSendTransaction() {
            if (modalInstance.form.value <= 0) {
                modalInstance.formError = true;
            } else {
                modalInstance.formError = false;
                $uibModalInstance.close();

                let CREATE_TRANSACTIONS_PATH = apiBaseURL + "create-transaction"

                let createTransactionData = $.param({
                    cusipValue: modalInstance.form.cusip,
                    transactionAssetName : modalInstance.form.assetName,
                    transactionPurchaseCost : modalInstance.form.purchaseCost
                });

                let createTransactionHeaders = {
                    headers : {
                        "Content-Type": "application/x-www-form-urlencoded"
                    }
                };

                // Create Transaction and handles success / fail responses.
                $http.post(CREATE_TRANSACTIONS_PATH, createTransactionData, createTransactionHeaders).then(
                    modalInstance.displayMessage,
                    modalInstance.displayMessage
                );
            }
        };

    modalInstance.displayMessage = (message) => {
        const modalInstanceTwo = $uibModal.open({
            templateUrl: 'messageContent.html',
            controller: 'messageCtrl',
            controllerAs: 'modalInstanceTwo',
            resolve: { message: () => message }
        });

        // No behaviour on close / dismiss.
        modalInstanceTwo.result.then(() => {}, () => {});
    };

    // Close create Transaction modal dialogue.
    modalInstance.cancel = () => $uibModalInstance.dismiss();

    // Validate the Transaction. ToDo See buyer stuff
    function invalidFormInput() {
        return (modalInstance.form.cusip === undefined);
    }
});

 //isNaN(modalInstance.form.purchaseCost) ||
// Controller for success/fail modal dialogue.
app.controller('messageCtrl', function ($uibModalInstance, message) {
    const modalInstanceTwo = this;
    modalInstanceTwo.message = message.data;
});


app.controller('TransferCtrl', function ($http, $location, $uibModalInstance, $uibModal, idpApp, apiBaseURL, peers) {
    const modalTransfer = this;

    modalTransfer.peers = peers;
    modalTransfer.form = {};
    modalTransfer.formError = false;

        // Validates and sends Transfer.
        modalTransfer.create = function validateAndSendTransaction() {
            if (modalTransfer.form.value <= 0) {
                modalTransfer.formError = true;
            } else {
                modalTransfer.formError = false;
                $uibModalTransfer.close();

                let CREATE_TRANSACTIONS_PATH = apiBaseURL + "create-transfer"

                let createTransferData = $.param({
                    cusipValueTr: modalTransfer.form.cusipTr,
                    transferAssetName : modalTransfer.form.assetNameTr,
                    transferPurchaseCost : modalTransfer.form.purchaseCostTr
                    transferBuyer : "O=SecurityBuyer"
                    transferLocation : "L=New York"
                    transferCountry : "C-US"
                });

                let createTransferHeaders = {
                    headers : {
                        "Content-Type": "application/x-www-form-urlencoded"
                    }
                };

                // Create Transaction and handles success / fail responses.
                $http.post(CREATE_TRANSACTIONS_PATH, createTransferData, createTransferHeaders).then(
                    modalTransfer.displayMessage,
                    modalTransfer.displayMessage
                );
            }
        };

    modalTransfer.displayMessage = (message) => {
        const modalInstanceTwo = $uibModal.open({
            templateUrl: 'messageContent.html',
            controller: 'messageCtrl',
            controllerAs: 'modalInstanceTwo',
            resolve: { message: () => message }
        });

        // No behaviour on close / dismiss.
        modalInstanceTwo.result.then(() => {}, () => {});
    };

    // Close create Transaction modal dialogue.
    modalTransfer.cancel = () => $uibModalTransfer.dismiss();

    // Validate the Transaction. ToDo See buyer stuff
    function invalidFormInput() {
        return (modalTransfer.form.cusip === undefined);
    }
});