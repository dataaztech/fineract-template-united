/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.savings.service;

import static org.apache.fineract.portfolio.savings.SavingsApiConstants.SAVINGS_PRODUCT_RESOURCE_NAME;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.accountingRuleParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.chargesParamName;
import static org.apache.fineract.portfolio.savings.SavingsApiConstants.taxGroupIdParamName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.persistence.PersistenceException;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.fineract.accounting.producttoaccountmapping.service.ProductToGLAccountMappingWritePlatformService;
import org.apache.fineract.infrastructure.codes.domain.CodeValue;
import org.apache.fineract.infrastructure.codes.domain.CodeValueRepositoryWrapper;
import org.apache.fineract.infrastructure.core.api.JsonCommand;
import org.apache.fineract.infrastructure.core.data.ApiParameterError;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResult;
import org.apache.fineract.infrastructure.core.data.CommandProcessingResultBuilder;
import org.apache.fineract.infrastructure.core.data.DataValidatorBuilder;
import org.apache.fineract.infrastructure.core.exception.GeneralPlatformDomainRuleException;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.apache.fineract.infrastructure.core.exception.PlatformDataIntegrityException;
import org.apache.fineract.infrastructure.entityaccess.domain.FineractEntityAccessType;
import org.apache.fineract.infrastructure.entityaccess.service.FineractEntityAccessUtil;
import org.apache.fineract.infrastructure.security.service.PlatformSecurityContext;
import org.apache.fineract.portfolio.charge.domain.Charge;
import org.apache.fineract.portfolio.charge.domain.ChargeTimeType;
import org.apache.fineract.portfolio.savings.DepositAccountType;
import org.apache.fineract.portfolio.savings.SavingsApiConstants;
import org.apache.fineract.portfolio.savings.data.SavingsProductDataValidator;
import org.apache.fineract.portfolio.savings.domain.SavingsProduct;
import org.apache.fineract.portfolio.savings.domain.SavingsProductAssembler;
import org.apache.fineract.portfolio.savings.domain.SavingsProductFloatingInterestRate;
import org.apache.fineract.portfolio.savings.domain.SavingsProductFloatingInterestRateRepository;
import org.apache.fineract.portfolio.savings.domain.SavingsProductRepository;
import org.apache.fineract.portfolio.savings.exception.SavingsProductNotFoundException;
import org.apache.fineract.portfolio.tax.domain.TaxGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SavingsProductWritePlatformServiceJpaRepositoryImpl implements SavingsProductWritePlatformService {

    private static final Logger LOG = LoggerFactory.getLogger(SavingsProductWritePlatformServiceJpaRepositoryImpl.class);
    private final PlatformSecurityContext context;
    private final SavingsProductRepository savingProductRepository;
    private final SavingsProductDataValidator fromApiJsonDataValidator;
    private final SavingsProductAssembler savingsProductAssembler;
    private final ProductToGLAccountMappingWritePlatformService accountMappingWritePlatformService;
    private final FineractEntityAccessUtil fineractEntityAccessUtil;
    private final SavingsProductFloatingInterestRateRepository savingsProductFloatingInterestRateRepository;

    private final CodeValueRepositoryWrapper codeValueRepository;

    @Autowired
    public SavingsProductWritePlatformServiceJpaRepositoryImpl(final PlatformSecurityContext context,
            final SavingsProductRepository savingProductRepository, final SavingsProductDataValidator fromApiJsonDataValidator,
            final SavingsProductAssembler savingsProductAssembler,
            final ProductToGLAccountMappingWritePlatformService accountMappingWritePlatformService,
            final SavingsProductFloatingInterestRateRepository savingsProductFloatingInterestRateRepository,
            final FineractEntityAccessUtil fineractEntityAccessUtil, CodeValueRepositoryWrapper codeValueRepository) {
        this.context = context;
        this.savingProductRepository = savingProductRepository;
        this.fromApiJsonDataValidator = fromApiJsonDataValidator;
        this.savingsProductAssembler = savingsProductAssembler;
        this.accountMappingWritePlatformService = accountMappingWritePlatformService;
        this.fineractEntityAccessUtil = fineractEntityAccessUtil;
        this.savingsProductFloatingInterestRateRepository = savingsProductFloatingInterestRateRepository;
        this.codeValueRepository = codeValueRepository;
    }

    /*
     * Guaranteed to throw an exception no matter what the data integrity issue is.
     */
    private void handleDataIntegrityIssues(final JsonCommand command, final Throwable realCause, final Exception dae) {

        if (realCause.getMessage().contains("sp_unq_name")) {

            final String name = command.stringValueOfParameterNamed("name");
            throw new PlatformDataIntegrityException("error.msg.product.savings.duplicate.name",
                    "Savings product with name `" + name + "` already exists", "name", name);
        } else if (realCause.getMessage().contains("sp_unq_short_name")) {

            final String shortName = command.stringValueOfParameterNamed("shortName");
            throw new PlatformDataIntegrityException("error.msg.product.savings.duplicate.short.name",
                    "Savings product with short name `" + shortName + "` already exists", "shortName", shortName);
        }

        logAsErrorUnexpectedDataIntegrityException(dae);
        throw new PlatformDataIntegrityException("error.msg.savingsproduct.unknown.data.integrity.issue",
                "Unknown data integrity issue with resource.");
    }

    private void logAsErrorUnexpectedDataIntegrityException(final Exception dae) {
        LOG.error("Error occured.", dae);
    }

    @Transactional
    @Override
    public CommandProcessingResult create(final JsonCommand command) {

        try {
            this.fromApiJsonDataValidator.validateForCreate(command.json());

            final SavingsProduct product = this.savingsProductAssembler.assemble(command);
            CodeValue productCategory = getProductCategory(command);
            if (productCategory != null) {
                product.setProductCategory(productCategory);
            }
            CodeValue productType = getLoanProductType(command);
            if (productType != null) {
                product.setProductType(productType);
            }

            this.savingProductRepository.saveAndFlush(product);

            // assemle floatingInterestRates
            final Set<SavingsProductFloatingInterestRate> floatingInterestRates = this.savingsProductAssembler
                    .assembleListOfFloatingInterestRates(command, product);
            // persist floatingInterestRates
            this.savingsProductFloatingInterestRateRepository.saveAll(floatingInterestRates);

            // save accounting mappings
            this.accountMappingWritePlatformService.createSavingProductToGLAccountMapping(product.getId(), command,
                    DepositAccountType.SAVINGS_DEPOSIT);

            // check if the office specific products are enabled. If yes, then
            // save this savings product against a specific office
            // i.e. this savings product is specific for this office.
            fineractEntityAccessUtil.checkConfigurationAndAddProductResrictionsForUserOffice(
                    FineractEntityAccessType.OFFICE_ACCESS_TO_SAVINGS_PRODUCTS, product.getId());

            return new CommandProcessingResultBuilder() //
                    .withEntityId(product.getId()) //
                    .build();
        } catch (final DataAccessException e) {
            handleDataIntegrityIssues(command, e.getMostSpecificCause(), e);
            return CommandProcessingResult.empty();
        } catch (final PersistenceException dve) {
            Throwable throwable = ExceptionUtils.getRootCause(dve.getCause());
            handleDataIntegrityIssues(command, throwable, dve);
            return CommandProcessingResult.empty();
        }
    }

    @Transactional
    @Override
    public CommandProcessingResult update(final Long productId, final JsonCommand command) {

        try {
            this.context.authenticatedUser();
            final SavingsProduct product = this.savingProductRepository.findById(productId)
                    .orElseThrow(() -> new SavingsProductNotFoundException(productId));

            CodeValue productCategory = getProductCategory(command);
            if (productCategory != null) {
                product.setProductCategory(productCategory);
            }

            CodeValue productType = getLoanProductType(command);
            if (productType != null) {
                product.setProductType(productType);
            }

            if (productCategory != null || productType != null) {
                this.savingProductRepository.saveAndFlush(product);
            }

            this.fromApiJsonDataValidator.validateForUpdate(command.json(), product);

            final Map<String, Object> changes = product.update(command);

            if (changes.containsKey(chargesParamName)) {
                final Set<Charge> savingsProductCharges = this.savingsProductAssembler.assembleListOfSavingsProductCharges(command,
                        product.currency().getCode());
                validateSavingsProductHasWithdrawalFeeSetWhenWithdrawFrequencyIsApplied(command, savingsProductCharges);
                final boolean updated = product.update(savingsProductCharges);
                if (!updated) {
                    changes.remove(chargesParamName);
                }
            }

            if (changes.containsKey(taxGroupIdParamName)) {
                final TaxGroup taxGroup = this.savingsProductAssembler.assembleTaxGroup(command);
                product.setTaxGroup(taxGroup);
                if (product.withHoldTax() && product.getTaxGroup() == null) {
                    final List<ApiParameterError> dataValidationErrors = new ArrayList<>();
                    final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)
                            .resource(SAVINGS_PRODUCT_RESOURCE_NAME);
                    final Long taxGroupId = null;
                    baseDataValidator.reset().parameter(taxGroupIdParamName).value(taxGroupId).notBlank();
                    throw new PlatformApiDataValidationException(dataValidationErrors);
                }
            }

            // accounting related changes
            final boolean accountingTypeChanged = changes.containsKey(accountingRuleParamName);
            final Map<String, Object> accountingMappingChanges = this.accountMappingWritePlatformService
                    .updateSavingsProductToGLAccountMapping(product.getId(), command, accountingTypeChanged, product.getAccountingType(),
                            DepositAccountType.SAVINGS_DEPOSIT);
            changes.putAll(accountingMappingChanges);

            if (!changes.isEmpty()) {
                this.savingProductRepository.saveAndFlush(product);
            }

            return new CommandProcessingResultBuilder() //
                    .withEntityId(product.getId()) //
                    .with(changes).build();
        } catch (final DataAccessException e) {
            handleDataIntegrityIssues(command, e.getMostSpecificCause(), e);
            return CommandProcessingResult.empty();
        } catch (final PersistenceException dve) {
            Throwable throwable = ExceptionUtils.getRootCause(dve.getCause());
            handleDataIntegrityIssues(command, throwable, dve);
            return CommandProcessingResult.empty();
        }
    }

    private static void validateSavingsProductHasWithdrawalFeeSetWhenWithdrawFrequencyIsApplied(JsonCommand command,
            Set<Charge> savingsProductCharges) {
        final Integer withdrawalFrequency = command.integerValueOfParameterNamed(SavingsApiConstants.WITHDRAWAL_FREQUENCY);
        final Integer withdrawalFrequencyEnum = command.integerValueOfParameterNamed(SavingsApiConstants.WITHDRAWAL_FREQUENCY_ENUM);

        if (withdrawalFrequency != null) {
            if (withdrawalFrequencyEnum == null) {
                throw new GeneralPlatformDomainRuleException(
                        "Please provide withdrawalFrequencyEnum since you provided withdrawalFrequency",
                        "Please provide withdrawalFrequencyEnum since you provided withdrawalFrequency");
            }

            if (CollectionUtils.isEmpty(savingsProductCharges)) {
                throw new GeneralPlatformDomainRuleException("withdrawalFrequency.requires.a.withdrawal.fee.charge.on.this.product",
                        "withdrawalFrequency requires a charge of ChargeTimeType [withdrawalFee ] on this product");
            }
            List<Charge> chargeList = new ArrayList<>();

            for (Charge charge : savingsProductCharges) {
                if (ChargeTimeType.fromInt(charge.getChargeTimeType()).equals(ChargeTimeType.WITHDRAWAL_FEE)) {
                    chargeList.add(charge);
                }
            }
            if (chargeList.size() == 0) {
                throw new GeneralPlatformDomainRuleException(
                        "ithdrawalFrequency.requires.a.withdrawal.fee.charge.on.this.product.but.it's not.supplied",
                        "withdrawalFrequency requires a charge of ChargeTimeType [withdrawalFee ] on this product but it's not supplied");

            }
        } else {
            if (withdrawalFrequencyEnum != null) {
                throw new GeneralPlatformDomainRuleException(
                        "Please provide withdrawalFrequency since you provided withdrawalFrequencyEnum",
                        "Please provide withdrawalFrequency since you provided withdrawalFrequencyEnum");
            }
        }
    }

    @Transactional
    @Override
    public CommandProcessingResult delete(final Long productId) {

        this.context.authenticatedUser();
        final SavingsProduct product = this.savingProductRepository.findById(productId)
                .orElseThrow(() -> new SavingsProductNotFoundException(productId));

        this.savingProductRepository.delete(product);

        return new CommandProcessingResultBuilder() //
                .withEntityId(product.getId()) //
                .build();
    }

    @Nullable
    private CodeValue getLoanProductType(JsonCommand command) {
        CodeValue productType = null;
        final Long productTypeId = command.longValueOfParameterNamed(SavingsApiConstants.savingsProductTypeIdParamName);
        if (productTypeId != null) {
            productType = this.codeValueRepository.findOneByCodeNameAndIdWithNotFoundDetection(SavingsApiConstants.SAVINGS_PRODUCT_TYPE,
                    productTypeId);
        }
        return productType;
    }

    @Nullable
    private CodeValue getProductCategory(JsonCommand command) {
        CodeValue productCategory = null;
        final Long productCategoryId = command.longValueOfParameterNamed(SavingsApiConstants.savingsProductCategoryIdParamName);
        if (productCategoryId != null) {
            productCategory = this.codeValueRepository
                    .findOneByCodeNameAndIdWithNotFoundDetection(SavingsApiConstants.SAVINGS_PRODUCT_CATEGORY, productCategoryId);
        }
        return productCategory;
    }

}
